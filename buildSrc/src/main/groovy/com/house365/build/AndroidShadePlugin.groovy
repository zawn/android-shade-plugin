/*
 * Copyright (c) 2015-2016, House365. All rights reserved.
 */

package com.house365.build

import com.android.annotations.NonNull
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.house365.build.transform.ShadeTransform
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.tasks.SourceSet

/**
 * Created by ZhangZhenli on 2016/1/6.
 */
public class AndroidShadePlugin implements Plugin<Project> {
    private Project project
    private BaseExtension android

    private LinkedHashMap<String, HashSet<String>> shadeConfigurations = new LinkedHashMap()

    @Override
    void apply(Project project) {
        this.project = project
        android = project.hasProperty("android") ? project.android : null
        this.android.getSourceSets().each { AndroidSourceSet sourceSet ->
            ConfigurationContainer configurations = project.getConfigurations();

            Configuration configuration = createConfiguration(configurations, sourceSet, "Classpath for only shade the " + sourceSet.getName() + " sources.")
            if (configuration != null && !shadeConfigurations.containsKey(configuration.getName()))
                shadeConfigurations.put(configuration.getName(), new HashSet<String>())

        }
        orderExtend(shadeConfigurations)
        this.android.getSourceSets().whenObjectAdded(new Action<AndroidSourceSet>() {
            @Override
            public void execute(AndroidSourceSet sourceSet) {
                ConfigurationContainer configurations = project.getConfigurations();
                Configuration configuration = createConfiguration(configurations, sourceSet, "Classpath for only shade the " + sourceSet.getName() + " sources.")
                if (configuration != null && !shadeConfigurations.containsKey(configuration.getName())) {
                    shadeConfigurations.put(configuration.getName(), new HashSet<String>())
                    orderExtend(shadeConfigurations)
                }
            }
        });
        LibraryExtension libraryExtension = (LibraryExtension) android
        if (android instanceof LibraryExtension) {
            ShadeTransform shadeTransform = new ShadeTransform(project, libraryExtension)
            libraryExtension.registerTransform(shadeTransform)
        } else {
            throw new ProjectConfigurationException("Unable to use shade for android application", null)
        }
        project.getGradle().addListener(new DependencyResolutionListener() {
            @Override
            void beforeResolve(ResolvableDependencies resolvableDependencies) {
                String name = resolvableDependencies.getName()
                if (name.startsWith("_") && name.endsWith("Compile") && !name.contains("UnitTest") && !name.contains("AndroidTest")) {
                    String configName = name.replace("Compile", "Shade").substring(1);
                    def configuration = project.configurations.getByName(configName)
                    if (configuration != null) {
                        project.configurations[name].extendsFrom(configuration)
                    }
                    HashSet<String> hashSet = shadeConfigurations.get(configName)
                    for (String ext : hashSet) {
                        configuration = project.configurations.findByName(ext)
                        if (configuration != null) {
                            project.configurations[name].extendsFrom(configuration)
                        }
                    }
                }
            }

            @Override
            void afterResolve(ResolvableDependencies resolvableDependencies) {}
        })
    }

    def orderExtend(LinkedHashMap<String, HashSet<String>> shadeConfigurations) {
        def keySet = shadeConfigurations.keySet();
        for (String conf : keySet) {
            for (String ext : keySet) {
                if (!conf.equals(ext) && ext.toLowerCase().contains(conf.toLowerCase())) {
                    shadeConfigurations.get(ext).add(conf)
                }
            }
        }
    }

    protected Configuration createConfiguration(
            @NonNull ConfigurationContainer configurations,
            @NonNull AndroidSourceSet sourceSet,
            @NonNull String configurationDescription) {
        println "sourceSet Name :" + sourceSet.getName()
        def shadeConfigurationName = getShadeConfigurationName(sourceSet.getName())
        def shadeConfiguration = configurations.findByName(shadeConfigurationName);
        if (shadeConfiguration == null) {
            shadeConfiguration = configurations.create(shadeConfigurationName);
            shadeConfiguration.setVisible(false);
            shadeConfiguration.setDescription(configurationDescription)
        }
        return shadeConfiguration
    }

    @NonNull
    public static String getShadeConfigurationName(String name) {
        if (name.equals(SourceSet.MAIN_SOURCE_SET_NAME)) {
            return "shade";
        } else {
            return String.format("%sShade", name);
        }
    }
}
