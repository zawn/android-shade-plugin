package com.house365.build;

import com.android.annotations.NonNull;
import com.android.build.api.transform.Transform;
import com.android.build.gradle.AndroidGradleOptions;
import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.BasePlugin;
import com.android.build.gradle.LibraryExtension;
import com.android.build.gradle.internal.SdkHandler;
import com.android.build.gradle.internal.TaskFactory;
import com.android.build.gradle.internal.TaskManager;
import com.android.build.gradle.internal.core.GradleVariantConfiguration;
import com.android.build.gradle.internal.dependency.VariantDependencies;
import com.android.build.gradle.internal.dsl.BuildType;
import com.android.build.gradle.internal.dsl.CoreBuildType;
import com.android.build.gradle.internal.dsl.ProductFlavor;
import com.android.build.gradle.internal.dsl.SigningConfig;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.pipeline.TransformTask;
import com.android.build.gradle.internal.publishing.AndroidArtifacts;
import com.android.build.gradle.internal.scope.AndroidTask;
import com.android.build.gradle.internal.scope.AndroidTaskRegistry;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.TaskConfigAction;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.FileSupplier;
import com.android.build.gradle.internal.transforms.ProGuardTransform;
import com.android.build.gradle.internal.variant.LibraryVariantData;
import com.android.build.gradle.tasks.ManifestProcessorTask;
import com.android.build.gradle.tasks.MergeResources;
import com.android.build.gradle.tasks.MergeSourceSetFolders;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.dependency.DependencyMutableData;
import com.android.builder.dependency.MavenCoordinatesImpl;
import com.android.builder.dependency.level2.AndroidDependency;
import com.android.builder.dependency.level2.DependencyNode;
import com.android.builder.dependency.level2.JavaDependency;
import com.android.builder.model.MavenCoordinates;
import com.android.builder.model.SourceProvider;
import com.android.ide.common.res2.AssetSet;
import com.android.ide.common.res2.ResourceSet;
import com.android.manifmerger.ManifestMerger2;
import com.android.utils.FileUtils;
import com.android.utils.StringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.house365.build.transform.ShadeJniLibsTransform;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodInvokeUtils;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.internal.ConventionMapping;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.Pair;
import org.gradle.internal.reflect.Instantiator;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import groovy.lang.GroovyObject;

import static com.android.build.gradle.internal.TaskManager.DIR_BUNDLES;

/**
 * Shade task manager for creating taskFactory in an Android library project.
 * <p/>
 * Created by ZhangZhenli on 2016/3/29.
 */
public class ShadeTaskManager {

    protected static Logger logger = Logging.getLogger(ShadeTaskManager.class);

    private static final boolean DEBUG = logger.isEnabled(LogLevel.INFO);
    ;
    private final BasePlugin basePlugin;
    private final BaseExtension android;
    private final AndroidBuilder androidBuilder;
    private final SdkHandler sdkHandler;
    private final Collection<BuildType> buildTypes;
    private final Collection<ProductFlavor> productFlavors;
    private final Collection<SigningConfig> signingConfigs;
    private final boolean isLibrary;
    private final Project project;
    private final TaskManager taskManager;
    private TaskFactory taskFactory;
    private final Instantiator instantiator;

    private final AndroidTaskRegistry androidTasks;


    private final Map<String, List<JavaDependency>> variantShadeJars = new LinkedHashMap<>();
    private final Map<String, List<AndroidDependency>> variantShadeLibraries = new LinkedHashMap<>();

    public List<JavaDependency> getVariantShadeJars(String variantName) {
        return variantShadeJars.get(variantName);
    }

    public List<AndroidDependency> getVariantShadeLibraries(String variantName) {
        return variantShadeLibraries.get(variantName);
    }

    public ShadeTaskManager(Project project, TaskFactory tasks, Instantiator instantiator,
                            BasePlugin basePlugin,
                            BaseExtension baseExtension) throws IllegalAccessException {
        this.project = project;
        this.taskFactory = tasks;
        this.instantiator = instantiator;
        this.basePlugin = basePlugin;
        this.android = baseExtension;
        try {
            this.androidBuilder = (AndroidBuilder) FieldUtils.readField(android, "androidBuilder", true);
            this.sdkHandler = (SdkHandler) FieldUtils.readField(android, "sdkHandler", true);
            this.taskManager = (TaskManager) FieldUtils.readField(basePlugin, "taskManager", true);
            this.androidTasks = taskManager.getAndroidTasks();
        } catch (IllegalAccessException e) {
            throw e;
        }
        this.buildTypes = android.getBuildTypes();
        this.productFlavors = android.getProductFlavors();
        this.signingConfigs = android.getSigningConfigs();
        this.isLibrary = android instanceof LibraryExtension ? true : false;
    }

    public void createTasksForVariantScope(TaskFactory tasks, VariantScope variantScope) {
        logger.debug("createTasksForVariantScope() called with: tasks = [" + tasks + "], variantScope = [" + variantScope + "]");

    }


    public void createTasksForVariantData(TaskManager taskManager,
                                          LibraryVariantData variantData) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        final LibraryVariantData libVariantData = (LibraryVariantData) variantData;
        final GradleVariantConfiguration variantConfig = variantData.getVariantConfiguration();
        final CoreBuildType buildType = variantConfig.getBuildType();

        final VariantScope variantScope = variantData.getScope();
        GlobalScope globalScope = variantScope.getGlobalScope();

        final File intermediatesDir = globalScope.getIntermediatesDir();
        final Collection<String> variantDirectorySegments = variantConfig.getDirectorySegments();
        final File variantBundleDir = FileUtils.join(
                intermediatesDir,
                StringHelper.toStrings(DIR_BUNDLES, variantDirectorySegments));

        // 当前Variant所有Shade的依赖(包含JAR以及AAR).
        Set<MavenCoordinates> mavenCoordinates = findVariantShadeDependenciesMavenCoordinates(project, variantData);

        // 当前Variant需要Shade的AAR依赖.
        LinkedList<com.android.builder.dependency.level2.Dependency> shadeAllDependencies = transformMavenCoordinatesToDependency(variantData, mavenCoordinates);
        List<AndroidDependency> shadeAndroidDependencies = shadeAllDependencies.stream()
                .filter(it -> it instanceof AndroidDependency)
                .map(it -> (AndroidDependency) it)
                .collect(Collectors.toList());
        List<JavaDependency> shadeJavaDependencies = shadeAllDependencies.stream()
                .filter(it -> it instanceof JavaDependency)
                .map(it -> (JavaDependency) it)
                .collect(Collectors.toList());
        shadeAndroidDependencies.stream()
                .filter(it -> it.getLocalJars().size() > 0)
                .map(it -> {
                    List<JavaDependency> list = new ArrayList<>();
                    for (File localJarFile : it.getLocalJars()) {
                        list.add(new JavaDependency(localJarFile));
                    }
                    return list;
                })
                .forEach(it -> {
                    shadeJavaDependencies.addAll(it);
                });
        variantShadeLibraries.put(variantData.getName(), shadeAndroidDependencies);
        variantShadeJars.put(variantData.getName(), shadeJavaDependencies);

        if (DEBUG) {
            for (AndroidDependency dependency : shadeAndroidDependencies) {
                System.out.println(dependency);
            }
        }




        // Shade合并Jar及其关联依赖以及Shade的AAR的关联Jar依赖至本地依赖.
        DependencyGraph originalCompileGraph = (DependencyGraph) MethodInvokeUtils.invokeMethod(variantData.getVariantDependency(), "getCompileGraph");
        DependencyGraph originalPackageGraph = (DependencyGraph) MethodInvokeUtils.invokeMethod(variantData.getVariantDependency(), "getPackageGraph");
        DependencyContainer originalPackageDependencies = variantData.getVariantDependency().getPackageDependencies();
        HashMap<Object, com.android.builder.dependency.level2.Dependency> originalPackageGraphDependencyMap = new HashMap<>(originalPackageGraph.getDependencyMap());
        ArrayList<DependencyNode> originalPackageGraphDependencies = new ArrayList<>(originalPackageGraph.getDependencies());
        Field isLocal = FieldUtils.getDeclaredField(JavaDependency.class, "isLocal", true);
        originalCompileGraph.getDependencyMap().entrySet().stream()
                .filter(it -> mavenCoordinates.contains(it.getValue().getCoordinates()))
                .filter(it -> it.getValue() instanceof JavaDependency)
                .map(it -> (JavaDependency) it.getValue())
                .forEach(it -> {
                    try {
                        isLocal.setBoolean(it, true);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                });
        originalCompileGraph.getDependencyMap().entrySet().stream()
                .filter(it -> mavenCoordinates.contains(it.getValue().getCoordinates()))
                .filter(it -> !originalPackageGraphDependencyMap.containsKey(it.getKey()))
                .forEach(it -> {
                    originalPackageGraphDependencyMap.put(it.getKey(), it.getValue());
                });
        originalCompileGraph.getDependencies().stream()
                .map(it -> Pair.of(it, originalCompileGraph.getDependencyMap().get(it.getAddress())))
                .filter(it -> mavenCoordinates.contains(it.getRight().getCoordinates()))
                .forEach(it -> {
                    originalPackageGraphDependencies.add(it.getLeft());
                });
        Field dataMapField = FieldUtils.getField(originalCompileGraph.getMutableDependencyDataMap().getClass(), "dataMap", true);
        @SuppressWarnings("unchecked")
        Map<com.android.builder.dependency.level2.Dependency, DependencyMutableData> dataMap = (Map<com.android.builder.dependency.level2.Dependency, DependencyMutableData>) dataMapField.get(originalCompileGraph.getMutableDependencyDataMap());
        shadeAllDependencies.stream().forEach(library -> {
                    DependencyMutableData dependencyMutableData = dataMap.computeIfAbsent(library, k -> new DependencyMutableData());
                    dependencyMutableData.setProvided(false);
                }
        );

        DependencyGraph compileGraph = new DependencyGraph(originalCompileGraph.getDependencyMap(), originalCompileGraph.getDependencies(), originalCompileGraph.getMutableDependencyDataMap());
        DependencyGraph packageGraph = new DependencyGraph(originalPackageGraphDependencyMap, originalPackageGraphDependencies, originalPackageGraph.getMutableDependencyDataMap());
        // 使用Shade处理后的值替换原有值.
        variantData.getVariantDependency().setDependencies(compileGraph, packageGraph, true);
        variantData.getVariantConfiguration().setResolvedDependencies(
                variantData.getVariantDependency().getCompileDependencies(),
                variantData.getVariantDependency().getPackageDependencies());

        Transform proguardTransform = getTransform(variantScope, ProGuardTransform.class);
        if (proguardTransform != null) {
            String taskName = getTransformTaskName(variantScope, proguardTransform);
            Task task = project.getTasks().findByName(taskName);
            task.doFirst(new Action<Task>() {
                @Override
                public void execute(Task task) {
                    // 处理已经Shade AAR的ClassPath.
                    variantData.getVariantConfiguration().setResolvedDependencies(
                            variantData.getVariantDependency().getCompileDependencies(),
                            originalPackageDependencies);
                }
            });
        }

        if (shadeAndroidDependencies != null && shadeAndroidDependencies.size() > 0) {

            appendShadeResourcesTask(variantData, shadeAndroidDependencies);

            appendShadeAssetsTask(variantData, shadeAndroidDependencies);

            createMergeLibraryManifestsTask(taskFactory, variantData.getScope());

            createSyncShadeJniLibsTransform(variantData, variantScope);
        }
    }

    private void createSyncShadeJniLibsTransform(LibraryVariantData variantData,
                                                 VariantScope variantScope) {
        TransformManager transformManager = variantScope.getTransformManager();
        Optional<AndroidTask<TransformTask>> taskOptional
                = transformManager.addTransform(taskFactory, variantScope, new ShadeJniLibsTransform(this, variantData));
        AndroidTask<TransformTask> shadeJniLibsAndroidTask = taskOptional.get();
        AndroidTask<?> syncJniLibsAndroidTask = transformManager.getTaskRegistry().get(shadeJniLibsAndroidTask.getName().replace("ShadeJniLibs", "SyncJniLibs"));
        List<AndroidTask<? extends Task>> downstreamTasks = syncJniLibsAndroidTask.getDownstreamTasks();
        downstreamTasks.stream().forEach(it -> {
            it.dependsOn(taskFactory, shadeJniLibsAndroidTask);
        });
        shadeJniLibsAndroidTask.dependsOn(taskFactory, syncJniLibsAndroidTask);
        Task bundle = project.getTasks().findByName(variantScope.getTaskName("bundle"));
        taskOptional.ifPresent(t -> bundle.dependsOn(t.getName()));
    }

    private String getTransformTaskName(VariantScope variantScope,
                                        Transform transform) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        String taskNamePrefix = (String) MethodInvokeUtils.invokeStaticMethod(variantScope.getTransformManager().getClass(), "getTaskNamePrefix", transform);
        return variantScope.getTaskName(taskNamePrefix);
    }

    /**
     * 获取Proguard实例.
     *
     * @param variantScope
     * @param transformClass
     * @return
     * @throws IllegalAccessException
     */
    private Transform getTransform(VariantScope variantScope,
                                   Class<? extends Transform> transformClass) throws IllegalAccessException {
        Field transformsField = FieldUtils.getField(variantScope.getTransformManager().getClass(), "transforms", true);
        @SuppressWarnings("unchecked")
        List<Transform> transforms = (List<Transform>) transformsField.get(variantScope.getTransformManager());
        return transforms.stream().filter(it -> it.getClass().equals(transformClass)).findFirst().orElse(null);
    }

    /**
     * 将AAR中的Resource附加现有的任务参数中.
     *
     * @param variantData
     * @param libraryDependencies
     */
    public void appendShadeResourcesTask(
            LibraryVariantData variantData,
            List<AndroidDependency> libraryDependencies) {

        logger.info("ShadeTaskManager.appendShadeResourcesTask");
        final String taskName = variantData.getScope().getTaskName("package", "Resources");
        final MergeResources mergeResourcesTask = (MergeResources) taskFactory.named(taskName);

        final LinkedHashSet<ResourceSet> resourceSets = Sets.newLinkedHashSet();
        final boolean validateEnabled = AndroidGradleOptions.isResourceValidationEnabled(
                variantData.getScope().getGlobalScope().getProject());
        for (int n = libraryDependencies.size() - 1; n >= 0; n--) {
            AndroidDependency dependency = libraryDependencies.get(n);
            logger.info("ResFolder: " + dependency.getResFolder());
            File resFolder = dependency.getResFolder();
            if (!resFolder.isFile()) {
                ResourceSet resourceSet =
                        new ResourceSet(dependency.getExtractedFolder().getName(), validateEnabled);
                resourceSet.addSource(resFolder);
                resourceSet.setFromDependency(true);
                resourceSets.add(resourceSet);
            }
        }

        ConventionMapping conventionMapping =
                (ConventionMapping) ((GroovyObject) mergeResourcesTask).getProperty("conventionMapping");

        resourceSets.addAll(conventionMapping.getConventionValue(new ArrayList<ResourceSet>(), "inputResourceSets", false));

        ConventionMappingHelper.map(mergeResourcesTask, "inputResourceSets", new Callable<List<ResourceSet>>() {
            @Override
            public List<ResourceSet> call() throws Exception {
                return Lists.newArrayList(resourceSets);
            }
        });
    }

    /**
     * 将Shade AAR中的Asset合并进bundle.
     *
     * @param variantData
     * @param libraryDependencies
     */
    public void appendShadeAssetsTask(
            LibraryVariantData variantData,
            List<AndroidDependency> libraryDependencies) {

        logger.info("ShadeTaskManager.appendShadeAssetsTask");
        MergeSourceSetFolders mergeAssetsTask = variantData.mergeAssetsTask;

        final LinkedHashSet<AssetSet> assetSets = Sets.newLinkedHashSet();
        for (int n = libraryDependencies.size() - 1; n >= 0; n--) {
            AndroidDependency dependency = libraryDependencies.get(n);
            File assetFolder = dependency.getAssetsFolder();
            logger.info("AssetFolder: " + assetFolder);
            if (!assetFolder.isFile()) {
                AssetSet assetSet = new AssetSet(dependency.getExtractedFolder().getName());
                assetSet.addSource(assetFolder);
                assetSets.add(assetSet);
            }
        }

        ConventionMapping conventionMapping =
                (ConventionMapping) ((GroovyObject) mergeAssetsTask).getProperty("conventionMapping");

        assetSets.addAll(conventionMapping.getConventionValue(new ArrayList<AssetSet>(), "inputDirectorySets", false));

        ConventionMappingHelper.map(mergeAssetsTask, "inputDirectorySets", new Callable<List<AssetSet>>() {
            @Override
            public List<AssetSet> call() throws Exception {
                return Lists.newArrayList(assetSets);
            }
        });
    }

    /**
     * 创建合并Android Manifest文件的Task,参考:{@link TaskManager#createMergeAppManifestsTask(TaskFactory, VariantScope)}
     *
     * @param tasks
     * @param variantScope
     */
    public void createMergeLibraryManifestsTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope variantScope) {
        LibraryVariantData libraryVariantData =
                (LibraryVariantData) variantScope.getVariantData();

        // loop on all outputs. The only difference will be the name of the task, and location
        // of the generated manifest
        for (final BaseVariantOutputData vod : libraryVariantData.getOutputs()) {
            VariantOutputScope scope = vod.getScope();

            List<ManifestMerger2.Invoker.Feature> optionalFeatures = getFeatures(variantScope);

            AndroidTask<? extends ManifestProcessorTask> processShadeManifestTask =
                    androidTasks.create(tasks, getMergeManifestConfig(scope, optionalFeatures));

            processShadeManifestTask.dependsOn(tasks, variantScope.getPrepareDependenciesTask());

            if (variantScope.getMicroApkTask() != null) {
                processShadeManifestTask.dependsOn(tasks, variantScope.getMicroApkTask());
            }
            for (AndroidTask<? extends Task> androidTask : scope.getManifestProcessorTask().getDownstreamTasks()) {
                androidTask.dependsOn(tasks, processShadeManifestTask);
            }
            libraryVariantData.generateBuildConfigTask.dependsOn(project.getTasks().findByName(processShadeManifestTask.getName()));
            processShadeManifestTask.dependsOn(tasks, scope.getManifestProcessorTask());
            scope.setManifestProcessorTask(processShadeManifestTask);
            addManifestArtifact(tasks, scope.getVariantScope().getVariantData());
        }
    }

    /**
     * 通过反射完成该功能,具体参考{@link TaskManager#createMergeAppManifestsTask(TaskFactory, VariantScope)}.
     *
     * @param variantScope
     * @return
     */
    private ImmutableList<ManifestMerger2.Invoker.Feature> getFeatures(
            @NonNull VariantScope variantScope) {
        try {
            Object incrementalMode = MethodInvokeUtils.invokeMethod(taskManager, "getIncrementalMode", variantScope.getVariantConfiguration());
            return !incrementalMode.toString().equals("NONE")
                    ? ImmutableList.of(ManifestMerger2.Invoker.Feature.INSTANT_RUN_REPLACEMENT)
                    : ImmutableList.of();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return ImmutableList.of();
    }

    /**
     * Adds the manifest artifact for the variant.
     * <p>
     * <p>This artifact is added if the publishNonDefault option is {@code true}. See variant
     * dependencies evaluation in {@link VariantDependencies} for more details.
     */
    private void addManifestArtifact(
            @NonNull TaskFactory tasks,
            @NonNull BaseVariantData<? extends BaseVariantOutputData> variantData) {
        if (variantData.getVariantDependency().getManifestConfiguration() != null) {
            ManifestProcessorTask mergeManifestsTask =
                    variantData.getMainOutput().getScope().getManifestProcessorTask().get(tasks);
            project.getArtifacts().add(
                    variantData.getVariantDependency().getManifestConfiguration().getName(),
                    AndroidArtifacts.buildManifestArtifact(globalScope.getProjectBaseName(),
                            new FileSupplier() {
                                @NonNull
                                @Override
                                public Task getTask() {
                                    return mergeManifestsTask;
                                }

                                @Override
                                public File get() {
                                    return mergeManifestsTask.getManifestOutputFile();
                                }
                            }));
        }
    }

    /**
     * Creates configuration action for the merge manifests task.
     */
    @NonNull
    protected TaskConfigAction<? extends ManifestProcessorTask> getMergeManifestConfig(
            @NonNull VariantOutputScope scope,
            @NonNull List<ManifestMerger2.Invoker.Feature> optionalFeatures) {
        return new MergeShadeManifests.ConfigAction(scope, optionalFeatures);
    }

    /**
     * 将{@link MavenCoordinates}类型的依赖转换为{@link com.android.builder.dependency.level2.Dependency}
     *
     * @param libraryVariantData
     * @param mavenCoordinates   所有需要Shade的依赖
     * @return
     */
    private static LinkedList<com.android.builder.dependency.level2.Dependency> transformMavenCoordinatesToDependency(
            LibraryVariantData libraryVariantData,
            Set<MavenCoordinates> mavenCoordinates) {
        ImmutableList<com.android.builder.dependency.level2.Dependency> allDependencies = libraryVariantData.getVariantConfiguration().getCompileDependencies().getAllDependencies();

        LinkedList<com.android.builder.dependency.level2.Dependency> shadeAndroidDependencies = new LinkedList<>();
        for (com.android.builder.dependency.level2.Dependency androidDependency : allDependencies) {
            List<MavenCoordinatesImpl> collect = mavenCoordinates.stream()
                    .map(it ->
                            (MavenCoordinatesImpl) it)
                    .filter(it ->
                            it.compareWithoutVersion(androidDependency.getCoordinates()))
                    .collect(Collectors.toList());
            if (collect.size() > 0) {
                shadeAndroidDependencies.add(androidDependency);
            }
        }
        return shadeAndroidDependencies;
    }

    /**
     * @param resolvedArtifact
     * @return
     * @see com.android.build.gradle.internal.DependencyManager
     */
    @NonNull
    private static MavenCoordinates createMavenCoordinates(
            @NonNull ResolvedArtifact resolvedArtifact) {
        return new MavenCoordinatesImpl(
                resolvedArtifact.getModuleVersion().getId().getGroup(),
                resolvedArtifact.getModuleVersion().getId().getName(),
                resolvedArtifact.getModuleVersion().getId().getVersion(),
                resolvedArtifact.getExtension(),
                resolvedArtifact.getClassifier());
    }

    /**
     * 查找当前Variant相关的所有的Shade依赖.
     *
     * @param project
     * @param variantData
     * @return
     */
    private static Set<MavenCoordinates> findVariantShadeDependenciesMavenCoordinates(
            Project project,
            LibraryVariantData variantData) {
        Set<ResolvedArtifact> resolvedArtifacts = findVariantShadeDependenciesArtifacts(project, variantData);
        Set<MavenCoordinates> mavenCoordinates = new LinkedHashSet<>();
        for (ResolvedArtifact it : resolvedArtifacts) {
            mavenCoordinates.add(createMavenCoordinates(it));
        }
        return mavenCoordinates;
    }

    /**
     * 查找当前Variant相关的所有的Shade依赖.
     *
     * @param project
     * @param libraryVariantData
     * @return
     */
    private static Set<ResolvedArtifact> findVariantShadeDependenciesArtifacts(
            Project project,
            LibraryVariantData libraryVariantData) {
        Set<Configuration> configurations = getVariantShadeConfigurations(project.getConfigurations(), libraryVariantData.getVariantConfiguration());
        Set<ResolvedArtifact> resolvedArtifacts = new LinkedHashSet<>();
        for (Configuration shadeConfiguration : configurations) {
            if (DEBUG) {
                System.out.println("Find configuration " + shadeConfiguration.getName());
                DependencySet dependencies = shadeConfiguration.getDependencies();
                for (Dependency dependency : dependencies) {
                    System.out.println(dependency);
                }
                System.out.println("Shade Configuration Files: ");
                for (File file : shadeConfiguration.getFiles()) {
                    System.out.println(file);
                }
                System.out.println();
            }
            resolvedArtifacts.addAll(shadeConfiguration.getResolvedConfiguration().getResolvedArtifacts());
        }
        if (DEBUG) {
            System.out.println("All Shade Files of Current Variant : ");
            for (ResolvedArtifact it : resolvedArtifacts) {
                System.out.println(it);
            }
        }
        return resolvedArtifacts;
    }

    /**
     * 从configurationContainer中查询variant指定的相关所有shade配置.
     *
     * @param configurationContainer
     * @param variantConfiguration
     * @return
     */
    private static Set<Configuration> getVariantShadeConfigurations(
            ConfigurationContainer configurationContainer,
            GradleVariantConfiguration variantConfiguration) {
        List<SourceProvider> sourceProviders = variantConfiguration.getSortedSourceProviders();
        Set<Configuration> configurations = new LinkedHashSet<>(sourceProviders.size(), 1);
        for (SourceProvider it : sourceProviders) {
            String shadeConfigurationName = ShadeExtension.getShadeConfigurationName(it.getName());
            configurations.add(configurationContainer.findByName(shadeConfigurationName));
        }
        return configurations;
    }
}
