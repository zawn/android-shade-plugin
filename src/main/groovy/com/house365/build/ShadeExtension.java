package com.house365.build;

import com.android.annotations.NonNull;
import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.LibraryExtension;
import com.android.build.gradle.api.AndroidSourceSet;
import com.android.build.gradle.internal.SdkHandler;
import com.android.build.gradle.internal.dsl.BuildType;
import com.android.build.gradle.internal.dsl.ProductFlavor;
import com.android.build.gradle.internal.dsl.SigningConfig;
import com.android.builder.core.AndroidBuilder;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.DependencyResolutionListener;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.SourceSet;
import org.gradle.internal.reflect.Instantiator;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Created by ZhangZhenli on 2016/3/30.
 */
public class ShadeExtension {

    private final BaseExtension android;
    private final AndroidBuilder androidBuilder;
    private final SdkHandler sdkHandler;
    private final Collection<BuildType> buildTypes;
    private final Collection<ProductFlavor> productFlavors;
    private final Collection<SigningConfig> signingConfigs;
    private final boolean isLibrary;
    private final ProjectInternal project;
    private final Instantiator instantiator;

    protected Logger logger;

    /**
     * 记录Shade
     */
    private ConfigurationCache configurationCache = new ConfigurationCache();

    public ShadeExtension(
            @NonNull final ProjectInternal project,
            @NonNull Instantiator instantiator,
            @NonNull BaseExtension android) throws IllegalAccessException {
        this.project = project;
        this.instantiator = instantiator;
        this.android = android;
        try {
            this.androidBuilder = (AndroidBuilder) FieldUtils.readField(android, "androidBuilder", true);
            this.sdkHandler = (SdkHandler) FieldUtils.readField(android, "sdkHandler", true);
        } catch (IllegalAccessException e) {
            throw e;
        }
        this.buildTypes = android.getBuildTypes();
        this.productFlavors = android.getProductFlavors();
        this.signingConfigs = android.getSigningConfigs();
        this.isLibrary = android instanceof LibraryExtension ? true : false;

        logger = Logging.getLogger(this.getClass());

        NamedDomainObjectContainer<AndroidSourceSet> sourceSets = this.android.getSourceSets();
        for (AndroidSourceSet sourceSet : sourceSets) {
            createConfiguration(sourceSet);
        }

        sourceSets.whenObjectAdded(new Action<AndroidSourceSet>() {
            @Override
            public void execute(AndroidSourceSet sourceSet) {
                createConfiguration(sourceSet);
            }
        });

        project.getGradle().addListener(dependencyResolutionListener);
    }

    protected void createConfiguration(AndroidSourceSet sourceSet) {
        String name = getShadeConfigurationName(sourceSet.getName());
        if (!name.matches("^test[A-Z].*") && !name.matches("^androidTest[A-Z].*")) {
            Configuration configuration = createConfiguration(
                    project.getConfigurations(),
                    name,
                    "Classpath for only shade the " + sourceSet.getName() + " sources.");
            configurationCache.addConfiguration(configuration);
        }
    }

    protected Configuration createConfiguration(
            @NonNull ConfigurationContainer configurations,
            @NonNull String configurationName,
            @NonNull String configurationDescription) {
        logger.info("Creating configuration {}", configurationName);

        Configuration configuration = configurations.findByName(configurationName);
        if (configuration == null) {
            configuration = configurations.create(configurationName);
        }
        configuration.setVisible(false);
        configuration.setDescription(configurationDescription);
        return configuration;
    }

    @NonNull
    public static String getShadeConfigurationName(String name) {
        if (name.equals(SourceSet.MAIN_SOURCE_SET_NAME)) {
            return "shade";
        } else {
            return String.format("%sShade", name);
        }
    }

    public HashSet<Configuration> getConfigurationAndExtends(@NonNull String configName) {
        return configurationCache.getConfigAndExtends(configName);
    }

    private DependencyResolutionListener dependencyResolutionListener = new DependencyResolutionListener() {
        @Override
        public void beforeResolve(ResolvableDependencies resolvableDependencies) {
            String name = resolvableDependencies.getName();
            if (resolvableDependencies.getPath().startsWith(project.getPath() + ":")
                    && name.startsWith("_")
                    && name.endsWith("Compile")
                    && !name.contains("UnitTest")
                    && !name.contains("AndroidTest")) {
                Configuration compileConfiguration = project.getConfigurations().findByName(name);
                if (compileConfiguration != null) {
                    String shadeConfigName = name.replace("Compile", "Shade").substring(1);
                    HashSet<Configuration> configurations = getConfigurationAndExtends(shadeConfigName);
                    if (configurations != null) {
                        for (Configuration configuration : configurations) {
                            compileConfiguration.extendsFrom(configuration);

                        }
                    }
                }
            }
        }

        @Override
        public void afterResolve(ResolvableDependencies resolvableDependencies) {
        }
    };

    /**
     * 缓存已添加的Configuration以及继承关系.
     */
    protected static class ConfigurationCache {

        private LinkedHashMap<String, Configuration> configurations = new LinkedHashMap();

        private LinkedHashMap<String, HashSet<String>> configExtendsMap = new LinkedHashMap();

        private boolean isComputeExtends = false;

        public ConfigurationCache() {
        }

        public void addConfiguration(Configuration configuration) {
            isComputeExtends = false;
            configurations.put(configuration.getName(), configuration);
        }

        private void computeExtends() {
            if (isComputeExtends)
                return;
            Set<String> keySet = configurations.keySet();
            for (String parentConfig : keySet) {
                for (String config : keySet) {
                    if (!parentConfig.equals(config) && config.toLowerCase().contains(parentConfig.toLowerCase())) {
                        HashSet<String> configExtends = configExtendsMap.get(config);
                        if (configExtends == null) {
                            configExtends = new HashSet<String>(keySet.size() / 2);
                            configExtendsMap.put(config, configExtends);
                        }
                        configExtends.add(parentConfig);
                    }
                }
            }
            isComputeExtends = true;
        }

        public HashSet<Configuration> getConfigAndExtends(@NonNull String configName) {
            computeExtends();
            HashSet<Configuration> hashSet = new HashSet<Configuration>(configurations.size() / 2, 1);
            hashSet.add(configurations.get(configName));
            HashSet<String> configNameSet = configExtendsMap.get(configName);
            if (configNameSet == null) {
                return hashSet;
            } else {
                for (String key : configNameSet) {
                    hashSet.add(configurations.get(key));
                }
                return hashSet;
            }
        }
    }
}
