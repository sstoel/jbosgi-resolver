/*
 * #%L
 * JBossOSGi Resolver API
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.jboss.osgi.resolver.spi;

import static org.jboss.osgi.resolver.ResolverLogger.LOGGER;
import static org.jboss.osgi.resolver.ResolverMessages.MESSAGES;
import static org.jboss.osgi.resolver.spi.ResolverHookProcessor.getCurrentProcessor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XCapability;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XPackageCapability;
import org.jboss.osgi.resolver.XPackageRequirement;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResource.State;
import org.jboss.osgi.resolver.XWiring;
import org.jboss.osgi.resolver.XWiringSupport;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.resource.Wiring;

/**
 * The abstract implementation of a {@link Environment}.
 *
 * @author thomas.diesler@jboss.com
 * @since 02-Jul-2010
 */
public class AbstractEnvironment implements XEnvironment, Cloneable {

    private final AtomicLong resourceIndex = new AtomicLong();
    private final Map<CacheKey, Set<Capability>> capabilityCache = new ConcurrentHashMap<CacheKey, Set<Capability>>();
    private final Map<String, Set<XResource>> resourceTypeCache = new ConcurrentHashMap<String, Set<XResource>>();
    private final Map<Long, XResource> resourceIndexCache = new ConcurrentHashMap<Long, XResource>();

    public AbstractEnvironment() {
    }

    private AbstractEnvironment(AbstractEnvironment env) {
        for (Entry<CacheKey, Set<Capability>> entry : env.capabilityCache.entrySet()) {
            Set<Capability> caps = getCachedCapabilities(entry.getKey());
            caps.addAll(entry.getValue());
        }
        for(Entry<String, Set<XResource>> entry : env.resourceTypeCache.entrySet()) {
            Set<XResource> resset = getCachedResources(entry.getKey());
            resset.addAll(entry.getValue());
        }
        resourceIndexCache.putAll(env.resourceIndexCache);
        // A resource that is installed into the copy gets an index that is not used yet
        synchronized (env.resourceIndex) {
            resourceIndex.set(env.resourceIndex.get());
        }
    }

    @Override
    public XEnvironment clone() {
        return new AbstractEnvironment(this);
    }

    @Override
    public Long nextResourceIdentifier(Long value, String symbolicName) {
        synchronized (resourceIndex) {
            if (value != null) {
                resourceIndex.addAndGet(Math.max(0, value - resourceIndex.get()));
                return value;
            } else {
                Long result = resourceIndex.incrementAndGet();
                LOGGER.tracef("Resource identifier for %s: [%d,%d]", symbolicName, value, result);
                return result;
            }
        }
    }

    @Override
    public synchronized void installResources(XResource... resources) {
        if (resources == null)
            throw MESSAGES.illegalArgumentNull("resources");

        for (XResource res : resources) {
            XIdentityCapability icap = res.getIdentityCapability();
            if (getCachedCapabilities(CacheKey.create(icap)).contains(icap))
                throw MESSAGES.illegalStateResourceAlreadyInstalled(res);

            LOGGER.debugf("Install resource: %s", res);

            // Add resource to index
            Long index = nextResourceIdentifier(res.getAttachment(XResource.RESOURCE_IDENTIFIER_KEY), icap.getName());
            res.putAttachment(XResource.RESOURCE_IDENTIFIER_KEY, index);
            resourceIndexCache.put(index, res);

            // Add resource by type
            getCachedResources(icap.getType()).add(res);

            // Add resource capabilites, a resource that cannot be installed is removed again
            try {
                for (Capability cap : res.getCapabilities(null)) {
                    for (CacheKey cachekey : CacheKey.createAll(cap)) {
                        getCachedCapabilities(cachekey).add(cap);
                    }
                    LOGGER.debugf("   %s", cap);
                }
            } catch (RuntimeException ex) {
                removeCachedResource(res, index);
                throw ex;
            }
            if (LOGGER.isDebugEnabled()) {
                for (Requirement req : res.getRequirements(null)) {
                    LOGGER.debugf("   %s", req);
                }
            }

            // Set the resource state
            AbstractResource absres = AbstractResource.assertAbstractResource(res);
            absres.setState(State.INSTALLED);
        }
    }

    @Override
    public synchronized void uninstallResources(XResource... resources) {
        if (resources == null)
            throw MESSAGES.illegalArgumentNull("resources");

        for (XResource res : resources) {

            // Remove resource by index
            Long index = res.getAttachment(XResource.RESOURCE_IDENTIFIER_KEY);
            if (index == null || resourceIndexCache.remove(index) == null) {
                LOGGER.debugf("Unknown resource: %s", res);
                continue;
            }

            LOGGER.debugf("Uninstall resource: %s", res);

            removeCachedResource(res, null);

            // Remove wirings
            res.getWiringSupport().refresh();

            // Set the resource state
            AbstractResource absres = AbstractResource.assertAbstractResource(res);
            absres.setState(State.UNINSTALLED);
        }
    }

    @Override
    public synchronized XResource getResourceById(long resId) {
        return resourceIndexCache.get(resId);
    }

    @Override
    public synchronized Iterator<XResource> getResources(Collection<String> types) {
        final Iterator<String> ittype = (types != null ? types : resourceTypeCache.keySet()).iterator();
        return new Iterator<XResource>() {
            Iterator<XResource> itres = Collections.<XResource>emptyList().iterator();
            @Override
            public boolean hasNext() {
                synchronized (AbstractEnvironment.this) {
                    while (!itres.hasNext() && ittype.hasNext()) {
                        Set<XResource> resources = getCachedResources(ittype.next());
                        itres = new LinkedHashSet<XResource>(resources).iterator();
                    }
                }
                return itres.hasNext();
            }

            @Override
            public XResource next() {
                return itres.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public List<Capability> findProviders(Requirement req) {
        if (req == null)
            throw MESSAGES.illegalArgumentNull("req");

        // The resolver hooks are foreign code, they are called without the monitor of the environment
        List<Capability> result = findProvidersLocked(req);
        return filterByHooks(req, result);
    }

    private synchronized List<Capability> findProvidersLocked(Requirement req) {

        XRequirement xreq = (XRequirement) req;
        CacheKey cachekey = CacheKey.create(req);
        List<Capability> result = new ArrayList<Capability>();
        for (Capability cap : findCachedCapabilities(cachekey)) {
            boolean matches = xreq.matches(cap);
            LOGGER.tracef("Env matching req %s with cap %s => %s", xreq, cap, matches);
            if (matches) {
                boolean ignoreCapability = false;
                XCapability xcap = (XCapability) cap;
                XResource capres = xcap.getResource();

                // Do not allow new wires to resources that are removed, unless they are removal pending: resolved and in use
                XWiringSupport wiringSupport = capres.getWiringSupport();
                if (!wiringSupport.isEffective() && !isRemovalPendingInUse(capres)) {
                    continue;
                }

                // Check if the package capability has been substituted
                Wiring wiring = wiringSupport.getWiring(true);
                if (wiring != null && xcap.adapt(XPackageCapability.class) != null) {
                    String pkgname = xcap.adapt(XPackageCapability.class).getPackageName();
                    for (Wire wire : wiring.getRequiredResourceWires(cap.getNamespace())) {
                        XRequirement wirereq = (XRequirement) wire.getRequirement();
                        XPackageRequirement preq = wirereq.adapt(XPackageRequirement.class);
                        if (pkgname.equals(preq.getPackageName())) {
                            ignoreCapability = true;
                            break;
                        }
                    }
                }
                if (ignoreCapability) {
                    continue;
                }

                // A fragment can only provide a capability if it is either already attached
                // or if there is one possible hosts that it can attach to
                // i.e. one of the hosts in the range is not resolved already
                List<Requirement> hostreqs = capres.getRequirements(HostNamespace.HOST_NAMESPACE);
                if (wiring == null && !hostreqs.isEmpty()) {
                    boolean unresolvedHost = false;
                    XRequirement hostreq = (XRequirement) hostreqs.get(0);
                    Set<Capability> hostcaps = capabilityCache.get(CacheKey.create(hostreq));
                    if (hostcaps != null) {
                        for (Capability hostcap : hostcaps) {
                            if (hostreq.matches(hostcap)) {
                                XResource host = (XResource) hostcap.getResource();
                                if (host.getWiringSupport().getWiring(true) == null) {
                                    unresolvedHost = true;
                                    break;
                                }
                            }
                        }
                    }
                    ignoreCapability = !unresolvedHost;
                }

                if (!ignoreCapability) {
                    result.add(cap);
                }
            }
        }
        return result;
    }

    private List<Capability> filterByHooks(Requirement req, List<Capability> result) {
        // Filter the matches by calling the registered {@link ResolverHook}s
        ResolverHookProcessor hookregs = getCurrentProcessor();
        if (hookregs != null && req instanceof BundleRequirement) {
            Collection<BundleCapability> bcaps = new ArrayList<BundleCapability>();
            for (Capability cap : result) {
                XResource res = (XResource) cap.getResource();
                if (res.getWiringSupport().getWiring(true) != null || hookregs.hasResource(res)) {
                    bcaps.add((BundleCapability) cap);
                }
            }
            bcaps = new RemoveOnlyCollection<BundleCapability>(bcaps);
            hookregs.filterMatches((BundleRequirement) req, bcaps);

            // Remove the filtered caps
            Iterator<Capability> iterator = result.iterator();
            while (iterator.hasNext()) {
                Capability cap = iterator.next();
                if (!bcaps.contains(cap)) {
                    iterator.remove();
                }
            }
        }

        LOGGER.tracef("Env provides: %s => %s", req, result);
        return result;
    }

    @Override
    public synchronized Map<Resource, Wiring> updateWiring(Map<Resource, List<Wire>> wiremap) {
        if (wiremap == null)
            throw MESSAGES.illegalArgumentNull("wiremap");

        Map<Resource, Wiring> result = new HashMap<Resource, Wiring>();
        for (Map.Entry<Resource, List<Wire>> entry : wiremap.entrySet()) {

            XResource requirer = (XResource) entry.getKey();
            List<Wire> reqwires = entry.getValue();
            XWiringSupport rwsupport = requirer.getWiringSupport();
            XWiring reqwiring = rwsupport.getWiring(true);
            if (reqwiring == null) {
                reqwiring = createWiring(requirer, reqwires, null);
                rwsupport.setWiring(reqwiring);
            } else {
                for (Wire wire : reqwires) {
                    reqwiring.addRequiredWire(wire);
                }
            }
            result.put(requirer, reqwiring);

            for (Wire wire : reqwires) {
                XResource provider = (XResource) wire.getProvider();
                XWiringSupport pwsupport = provider.getWiringSupport();
                XWiring provwiring = pwsupport.getWiring(true);
                if (provwiring == null) {
                    provwiring = createWiring(provider, null, null);
                    pwsupport.setWiring(provwiring);
                }
                provwiring.addProvidedWire(wire);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    @Override
    public synchronized Map<Resource, Wiring> getWirings() {
        Map<Resource, Wiring> result = new HashMap<Resource, Wiring>();
        for (XResource res : resourceIndexCache.values()) {
            // The wirings of removal pending resources that are in use are part of the existing wirings, the wirings of
            // other resources may depend on them
            Wiring wiring = res.getWiringSupport().getWiring(true);
            if (wiring == null && isRemovalPendingInUse(res))
                wiring = res.getWiringSupport().getWiring(false);
            if (wiring != null) {
                result.put(res, wiring);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    // A resource that is not effective any more, but resolved and still in use
    private static boolean isRemovalPendingInUse(XResource res) {
        XWiringSupport wiringSupport = res.getWiringSupport();
        if (wiringSupport.isEffective())
            return false;
        Wiring wiring = wiringSupport.getWiring(false);
        return wiring instanceof BundleWiring && ((BundleWiring) wiring).isInUse();
    }

    private XWiring createWiring(XResource res, List<Wire> required, List<Wire> provided) {
        if (res instanceof XBundleRevision) {
            return new AbstractBundleWiring((XBundleRevision) res, required, provided);
        } else {
            return new AbstractWiring(res, required, provided);
        }
    }

    // Remove the resource by type and its capabilities from the caches, and from the index if given
    private void removeCachedResource(XResource res, Long index) {
        if (index != null)
            resourceIndexCache.remove(index);
        XIdentityCapability icap = res.getIdentityCapability();
        getCachedResources(icap.getType()).remove(res);
        for (Capability cap : res.getCapabilities(null)) {
            for (CacheKey cachekey : CacheKey.createAll(cap)) {
                Set<Capability> cachecaps = capabilityCache.get(cachekey);
                if (cachecaps != null) {
                    cachecaps.remove(cap);
                    if (cachecaps.isEmpty()) {
                        capabilityCache.remove(cachekey);
                    }
                }
            }
        }
    }

    private synchronized Set<Capability> getCachedCapabilities(CacheKey key) {
        Set<Capability> capset = capabilityCache.get(key);
        if (capset == null) {
            capset = new LinkedHashSet<Capability>();
            capabilityCache.put(key, capset);
        }
        return capset;
    }

    private synchronized Set<Capability> findCachedCapabilities(CacheKey key) {
        Set<Capability> capset = capabilityCache.get(key);
        if (capset == null) {
            capset = new LinkedHashSet<Capability>();
            // do not add this to the capabilityCache
        }
        // Without a namespace value every capability of the namespace is a candidate, also those with another value
        if (key.value == null || key.hasFuzzyOperator()) {
            capset = new LinkedHashSet<Capability>();
            for (Entry<CacheKey, Set<Capability>> entry : capabilityCache.entrySet()) {
                CacheKey auxkey = entry.getKey();
                if (auxkey.namespace.equals(key.namespace)) {
                    capset.addAll(entry.getValue());
                }
            }
        }
        return Collections.unmodifiableSet(capset);
    }

    private Set<XResource> getCachedResources(String type) {
        Set<XResource> typeset = resourceTypeCache.get(type);
        if (typeset == null) {
            typeset = new LinkedHashSet<XResource>();
            resourceTypeCache.put(type, typeset);
        }
        return typeset;
    }

    private static class CacheKey {

        private final String namespace;
        private final String value;
        private final String keyspec;
        private final String operator;

        static CacheKey create(Capability cap) {
            return createAll(cap).get(0);
        }

        // A capability with several namespace values is found by each of them
        static List<CacheKey> createAll(Capability cap) {
            String namespace = cap.getNamespace();
            Object nsvalue = cap.getAttributes().get(namespace);
            List<CacheKey> keys = new ArrayList<CacheKey>();
            if (nsvalue instanceof Collection && !((Collection<?>) nsvalue).isEmpty()) {
                for (Object aux : (Collection<?>) nsvalue) {
                    CacheKey key = new CacheKey(namespace, aux != null ? aux.toString() : null, null);
                    if (!keys.contains(key))
                        keys.add(key);
                }
            } else {
                keys.add(new CacheKey(namespace, nsvalue != null ? nsvalue.toString() : null, null));
            }
            return keys;
        }

        static CacheKey create(Requirement req) {
            String namespace = req.getNamespace();
            StringBuffer opbuffer = new StringBuffer();
            String nsvalue = AbstractRequirement.getNamespaceValue(req, opbuffer);
            String operator = opbuffer.length() > 0 ? opbuffer.toString() : null;
            // The capabilities of the namespace value can only be looked up when every matching capability has that value,
            // otherwise all capabilities of the namespace must be considered
            if (nsvalue != null && !isRequiredEquality(AbstractRequirement.getFilterFromDirective(req).toString(), namespace, operator, nsvalue)) {
                nsvalue = null;
            }
            return new CacheKey(namespace, nsvalue, operator);
        }

        // True if the first namespace attribute of the filter is a plain equality that is not nested in an OR or NOT
        private static boolean isRequiredEquality(String filterstr, String namespace, String operator, String nsvalue) {
            if (!"=".equals(operator) || nsvalue.indexOf('*') >= 0 || nsvalue.indexOf('\\') >= 0) {
                return false;
            }
            String term = "(" + namespace + "=";
            StringBuilder enclosing = new StringBuilder();
            for (int i = 0; i < filterstr.length(); i++) {
                char ch = filterstr.charAt(i);
                if (ch == '\\') {
                    i++;
                } else if (ch == '(') {
                    if (filterstr.startsWith(term, i)) {
                        return enclosing.indexOf("|") < 0 && enclosing.indexOf("!") < 0;
                    }
                    enclosing.append(filterstr.charAt(i + 1));
                } else if (ch == ')') {
                    enclosing.setLength(enclosing.length() - 1);
                }
            }
            return false;
        }

        private CacheKey(String namespace, String value, String operator) {
            this.namespace = namespace;
            this.value = value;
            this.operator = operator;
            this.keyspec = namespace + ":" + value;
        }

        boolean hasFuzzyOperator() {
            return operator != null && !operator.equals("=");
        }

        @Override
        public int hashCode() {
            return keyspec.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof CacheKey))
                return false;
            CacheKey other = (CacheKey) obj;
            return keyspec.equals(other.keyspec);
        }

        @Override
        public String toString() {
            return "[" + keyspec + "]";
        }
    }
}
