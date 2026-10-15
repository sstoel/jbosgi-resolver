/*
 * Copyright (C) 2015 Computer Science Corporation
 * All rights reserved.
 *
 */
package org.jboss.osgi.resolver.spi;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.modules.DependencySpec;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleDependencySpec;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleLoader;
import org.jboss.modules.filter.PathFilter;
import org.jboss.modules.filter.PathFilters;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.OSGiMetaDataBuilder;
import org.jboss.osgi.resolver.XBundleRevision;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.resource.Capability;

/**
 * @author arcivanov
 */
public class OSGiMetaDataProcessor
{
    public static abstract class ModuleExportPackagesCollector
    {
        private final Module module;

        public ModuleExportPackagesCollector(Module module)
        {
            this.module = module;
        }

        public final void collectExportPackages()
        {
            for (String path : getExportedPaths(module, new HashMap<Module, Set<String>>())) {
                if (path.length() > 0) {
                    String packageName = path.replace('/', '.');
                    addPackage(packageName);
                }
            }
        }

        /**
         * The paths that a module exports: its own content and what the dependencies export that it re-exports. Not
         * Module.getExportedPaths, which are all the paths that the module sees, also those of its other dependencies.
         */
        private static Set<String> getExportedPaths(Module module, Map<Module, Set<String>> exported)
        {
            Set<String> result = exported.get(module);
            if (result != null)
                return result;
            result = new LinkedHashSet<String>();
            exported.put(module, result);

            result.addAll(module.getClassLoader().getLocalPaths());
            for (DependencySpec dep : module.getDependencies()) {
                PathFilter exportFilter = dep.getExportFilter();
                if (exportFilter == null || exportFilter.equals(PathFilters.rejectAll()))
                    continue;
                PathFilter importFilter = dep.getImportFilter() != null ? dep.getImportFilter() : PathFilters.acceptAll();
                if (dep instanceof ModuleDependencySpec) {
                    ModuleDependencySpec moddep = (ModuleDependencySpec) dep;
                    ModuleLoader loader = moddep.getModuleLoader() != null ? moddep.getModuleLoader() : module.getModuleLoader();
                    Module depModule;
                    try {
                        depModule = loader.loadModule(moddep.getName());
                    } catch (ModuleLoadException ex) {
                        continue;
                    }
                    for (String path : getExportedPaths(depModule, exported)) {
                        if (importFilter.accept(path) && exportFilter.accept(path))
                            result.add(path);
                    }
                }
                // The local dependencies provide the own content of the module, the JDK packages are system packages
            }
            return result;
        }

        protected abstract void addPackage(String packageName);
    }

    private OSGiMetaDataProcessor()
    {
    }

    public static OSGiMetaData loadOsgiMetaData(Module module)
    {
        // Get symbolic name & version
        ModuleIdentifier moduleId = module.getIdentifier();
        String symbolicName = moduleId.getName();
        Version version;
        try {
            version = Version.parseVersion(moduleId.getSlot());
        }
        catch (IllegalArgumentException ex) {
            version = Version.emptyVersion;
        }
        final OSGiMetaDataBuilder builder = OSGiMetaDataBuilder.createBuilder(symbolicName, version);

        // Add a package capability for every exported path
        new ModuleExportPackagesCollector(module) {

            @Override
            protected void addPackage(String packageName)
            {
                builder.addExportPackages(packageName);
            }
        }.collectExportPackages();
        return builder.getOSGiMetaData();
    }

    public static OSGiMetaData getOsgiMetaData(XBundleRevision brev)
    {
        OSGiMetaDataBuilder metaBuilder = OSGiMetaDataBuilder.createBuilder(brev.getSymbolicName(), brev.getVersion());
        List<Capability> exportedPaths = brev.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE);
        for (Capability cap : exportedPaths) {
            metaBuilder.addExportPackages(cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE).toString());
        }
        return metaBuilder.getOSGiMetaData();
    }
}
