/*
 * Copyright (c) 2015-2016, House365. All rights reserved.
 */

package com.house365.build

import com.android.annotations.NonNull
import com.android.build.api.transform.Transform
import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.internal.api.LibraryVariantImpl
import com.android.build.gradle.internal.dependency.ManifestDependencyImpl
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.scope.VariantOutputScope
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.variant.BaseVariantOutputData
import com.android.build.gradle.internal.variant.LibraryVariantData
import com.android.builder.dependency.LibraryDependency
import com.house365.build.task.ClassPathTask
import com.house365.build.task.LibraryManifestMergeTask
import com.house365.build.transform.ShadeJniLibsTransform
import com.house365.build.transform.ShadeJarTransform
import org.gradle.api.*
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.AbstractCompile

import java.lang.reflect.Method

/**
 * Created by ZhangZhenli on 2016/1/6.
 */
public class AndroidShadePlugin implements Plugin<Project> {
    private Project project
    private BaseExtension android

    private LinkedHashMap<String, HashSet<String>> shadeConfigurations = new LinkedHashMap()
    private ShadeJarTransform shadeJarTransform
    private ShadeJniLibsTransform shadeJniLibsTransform

    private static logger = org.slf4j.LoggerFactory.getLogger(ShadeJarTransform.class)

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
        if (android == null) {
            throw new ProjectConfigurationException("Only use shade for android library", null)
        }
        if (android instanceof LibraryExtension) {
            LibraryExtension libraryExtension = (LibraryExtension) android
            shadeJarTransform = new ShadeJarTransform(project, libraryExtension)
            shadeJniLibsTransform = new ShadeJniLibsTransform(project, libraryExtension)
            libraryExtension.registerTransform(this.shadeJarTransform)
            libraryExtension.registerTransform(this.shadeJniLibsTransform)
        } else {
            throw new ProjectConfigurationException("Unable to use shade for android application", null)
        }
        project.afterEvaluate {
            if (android instanceof LibraryExtension) {
                LibraryExtension libraryExtension = (LibraryExtension) android
                for (LibraryVariantImpl variant : libraryExtension.libraryVariants) {
                    println project.getName() + " " + variant.getDirName() + " ***********************************"
                    LibraryVariantData variantData = variant.variantData
                    VariantScope scope = variantData.getScope()
                    Method getTaskNamePrefixMethod = TransformManager.class.getDeclaredMethod("getTaskNamePrefix", Transform.class)
                    getTaskNamePrefixMethod.setAccessible(true);
                    String prefix = getTaskNamePrefixMethod.invoke(null, shadeJarTransform)
                    String taskName = scope.getTaskName(prefix);
                    // 大写第一个字母
                    String pinyin = String.valueOf(taskName.charAt(0)).toUpperCase().concat(taskName.substring(1));
                    def pathTask = project.tasks.create("pre" + pinyin, ClassPathTask)
                    pathTask.variantData = variantData
                    Task task = project.tasks.findByName(taskName)
                    task.dependsOn pathTask
                    AbstractCompile javaCompile = variant.hasProperty('javaCompiler') ? variant.javaCompiler : variant.javaCompile
                    javaCompile.classpath.each {
                        println it
                    }

                    LinkedHashSet<File> linkedHashSet = ShadeJarTransform.getNeedCombineFiles(project, variantData);
                    List<LibraryDependency> libraryDependencies = ShadeJarTransform.getNeedCombineAar(variantData, linkedHashSet)
                    ShadeJarTransform.addAssetsToBundle(variantData, libraryDependencies)
                    ShadeJarTransform.addResourceToBundle(variantData, libraryDependencies)
                    // Merge AndroidManifest.xml
                    println "Merge AndroidManifest.xml"
                    println libraryDependencies
                    List<ManifestDependencyImpl> libraries = LibraryManifestMergeTask.getManifestDependencies(libraryDependencies)
                    println "ManifestDependencyImpl\n" + libraries.toString()
                    def libManifestMergeTask = project.tasks.create(scope.getTaskName("process", "ShadeManifestMerge"), LibraryManifestMergeTask)
                    libManifestMergeTask.variantData = variantData
                    libManifestMergeTask.libraries = libraries
                    BaseVariantOutputData variantOutputData = scope.getVariantData().getOutputs().get(0);
                    def proecssorTask = project.tasks.findByName(variantOutputData.manifestProcessorTask.getName());
//                    proecssorTask.deleteAllActions()
                    proecssorTask.finalizedBy libManifestMergeTask
                    println project.getName() + " " + variant.getDirName() + " *********************************** end\n\n\n"
                }
            } else {
                println project.getName() + " " + variant.getDirName() + " ***********************************"
                AppExtension appExtension = (AppExtension) android
                for (ApplicationVariant variant : appExtension.applicationVariants) {
                    // Merge AndroidManifest.xml
                    println "Merge AndroidManifest.xml"
                    for (final BaseVariantOutputData vod : variantData.getOutputs()) {
                        VariantOutputScope variantOutputScope = vod.getScope();
                        println variantOutputScope.getManifestProcessorTask().getName()
                    }
                }
                println project.getName() + " " + variant.getDirName() + " *********************************** end\n\n\n"
            }

        }
        project.getGradle().addListener(new DependencyResolutionListener() {
            @Override
            void beforeResolve(ResolvableDependencies resolvableDependencies) {
                String name = resolvableDependencies.getName()
                if (resolvableDependencies.path.startsWith(project.path + ":")
                        && name.startsWith("_")
                        && name.endsWith("Compile")
                        && !name.contains("UnitTest")
                        && !name.contains("AndroidTest")) {
                    def config = project.configurations.findByName(name)
                    if (config != null) {
                        String shadeConfigName = name.replace("Compile", "Shade").substring(1);
                        def shadeConfig = project.configurations.findByName(shadeConfigName)
                        if (shadeConfig != null) {
                            config.extendsFrom(shadeConfig)
                        }
                        HashSet<String> hashSet = shadeConfigurations.get(shadeConfigName)
                        if (hashSet != null)
                            for (String ext : hashSet) {
                                shadeConfig = project.configurations.findByName(ext)
                                if (shadeConfig != null) {
                                    config.extendsFrom(shadeConfig)
                                }
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
        logger.info "sourceSet Name :" + sourceSet.getName()
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
