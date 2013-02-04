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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.jboss.osgi.resolver.XBundle;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Resource;

/**
 * The default implementation for {@link ResolverHook} functionality.
 *
 * @author thomas.diesler@jboss.com
 * @since 01-Feb-2013
 */
public class ResolverHookRegistrations {

    private static ThreadLocal<ResolverHookRegistrations> association = new ThreadLocal<ResolverHookRegistrations>();
    private List<ResolverHookRegistration> registrations;
    private Collection<BundleRevision> candidates;

    public ResolverHookRegistrations(BundleContext syscontext, Collection<XBundle> unresolved) {

        // Get the set of unresolved candidates
        candidates = new ArrayList<BundleRevision>();
        for (XBundle bundle : unresolved) {
            candidates.add(bundle.getBundleRevision());
        }
        candidates = new RemoveOnlyCollection<BundleRevision>(candidates);

        // Get the set of registered {@link ResolverFactory}
        Collection<ServiceReference<ResolverHookFactory>> srefs = null;
        try {
            srefs = syscontext.getServiceReferences(ResolverHookFactory.class, null);
        } catch (InvalidSyntaxException e) {
            // ignore
        }

        // The hooks are called in ranking order
        List<ServiceReference<ResolverHookFactory>> sorted = new ArrayList<ServiceReference<ResolverHookFactory>>(srefs);
        Collections.reverse(sorted);

        // Create list of {@link ResolverHook} from {@link ResolverFactory}
        for (ServiceReference<ResolverHookFactory> sref : sorted) {
            if (registrations == null) {
                registrations = new ArrayList<ResolverHookRegistration>();
            }
            registrations.add(new ResolverHookRegistration(sref));
        }
    }

    public static ResolverHookRegistrations getResolverHookRegistrations() {
        return association.get();
    }

    public boolean hasResolverHooks() {
        return registrations != null;
    }

    public boolean hasBundleRevision(BundleRevision brev) {
        return candidates != null && candidates.contains(brev);
    }

    public boolean hasResource(Resource res) {
        return candidates != null && candidates.contains(res);
    }

    public void begin(BundleContext syscontext, Collection<? extends Resource> mandatory, Collection<? extends Resource> optional) {

        // Get the initial set of trigger bundles
        Collection<BundleRevision> triggers = new ArrayList<BundleRevision>();
        if (mandatory != null) {
            for (Resource res : mandatory) {
                triggers.add((BundleRevision) res);
            }
        }
        if (optional != null) {
            for (Resource res : optional) {
                triggers.add((BundleRevision) res);
            }
        }
        triggers = new RemoveOnlyCollection<BundleRevision>(triggers);

        // Create a {@link ResolverHook} for each factory
        for (ResolverHookRegistration hookreg : registrations) {
            try {
                ResolverHookFactory hookFactory = syscontext.getService(hookreg.sref);
                hookreg.hook = hookFactory.begin(triggers);
            } catch (RuntimeException ex) {
                hookreg.lastException = ex;
                throw new ResolverHookException(ex);
            }
        }

        // Set the thread association
        association.set(this);
    }

    public void filterResolvable() {
        for (ResolverHookRegistration hookreg : registrations) {
            ResolverHook hook = hookreg.hook;
            if (hook != null && hookreg.lastException == null) {
                try {
                    hook.filterResolvable(candidates);
                } catch (RuntimeException ex) {
                    hookreg.lastException = ex;
                    throw new ResolverHookException(ex);
                }
            }
        }
    }

    public void filterMatches(BundleRequirement breq, Collection<BundleCapability> bcaps) {
        for (ResolverHookRegistration hookreg : registrations) {
            ResolverHook hook = hookreg.hook;
            if (hook != null && hookreg.lastException == null) {
                try {
                    hook.filterMatches(breq, bcaps);
                } catch (RuntimeException ex) {
                    hookreg.lastException = ex;
                    throw new ResolverHookException(ex);
                }
            }
        }
    }

    public void end() {

        // Call end on every {@link ResolverHook}
        for (ResolverHookRegistration hookreg : registrations) {
            ResolverHook hook = hookreg.hook;
            if (hook != null) {
                try {
                    hook.end();
                } catch (RuntimeException ex) {
                    hookreg.lastException = ex;
                }
            }
        }

        // Clear the thread association
        association.remove();
    }

    class ResolverHookRegistration {
        private final ServiceReference<ResolverHookFactory> sref;
        private RuntimeException lastException;
        private ResolverHook hook;

        ResolverHookRegistration(ServiceReference<ResolverHookFactory> sref) {
            this.sref = sref;
        }

        Bundle getBundle() {
            return sref.getBundle();
        }
    }
}