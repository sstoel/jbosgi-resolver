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
package org.jboss.test.osgi.resolver.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.resolver.XResourceBuilderFactory;
import org.junit.Test;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;

/**
 * Unit tests for the {@link org.osgi.service.resolver.Environment} class
 *
 * @author Thomas.Diesler@jboss.com
 */
public class XEnvironmentTestCase extends AbstractTestBase {

    @Test
    public void testFindProviders() throws Exception {

        XResourceBuilder<XResource> builderA = XResourceBuilderFactory.create();
        builderA.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "testA");
        builderA.addRequirement(PackageNamespace.PACKAGE_NAMESPACE, "org.jboss.foo");
        XResource resourceA = builderA.getResource();

        XResourceBuilder<XResource> builderB = XResourceBuilderFactory.create();
        builderB.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "testB");
        builderB.addCapability(PackageNamespace.PACKAGE_NAMESPACE, "org.jboss.foo");
        XResource resourceB = builderB.getResource();

        XResourceBuilder<XResource> builderC = XResourceBuilderFactory.create();
        builderC.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "testB");
        builderC.addCapability(PackageNamespace.PACKAGE_NAMESPACE, "org.jboss.foo");
        XResource resourceC = builderC.getResource();

        XEnvironment env = installResources(resourceA, resourceB, resourceC);

        List<Requirement> reqs = resourceA.getRequirements(PackageNamespace.PACKAGE_NAMESPACE);
        assertEquals(1, reqs.size());
        XRequirement req = (XRequirement) reqs.get(0);

        List<Capability> providers = env.findProviders(req);
        assertEquals(2, providers.size());
        assertSame(resourceB, providers.get(0).getResource());
        assertSame(resourceC, providers.get(1).getResource());
    }

    @Test
    public void testFindProvidersWithoutNamespaceAttribute() throws Exception {

        // A capability that has no attribute with the name of the namespace
        Map<String, Object> atts = new HashMap<String, Object>();
        atts.put("osgi.native.osname", "Linux");
        atts.put("osgi.native.processor", "x86-64");
        XResourceBuilder<XResource> builderA = XResourceBuilderFactory.create();
        builderA.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "testA");
        builderA.addCapability("osgi.native", atts, null);
        XResource resourceA = builderA.getResource();

        // The filter attributes start with the name of the namespace
        Map<String, String> dirs = new HashMap<String, String>();
        dirs.put("filter", "(&(osgi.native.osname~=linux)(osgi.native.processor~=x86-64))");
        XResourceBuilder<XResource> builderB = XResourceBuilderFactory.create();
        builderB.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "testB");
        builderB.addRequirement("osgi.native", null, dirs);
        XResource resourceB = builderB.getResource();

        XEnvironment env = installResources(resourceA, resourceB);

        List<Requirement> reqs = resourceB.getRequirements("osgi.native");
        assertEquals(1, reqs.size());

        List<Capability> providers = env.findProviders(reqs.get(0));
        assertEquals(1, providers.size());
        assertSame(resourceA, providers.get(0).getResource());
    }
}
