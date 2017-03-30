package com.house365.build;

import com.android.annotations.NonNull;
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
import com.android.build.gradle.internal.publishing.ManifestPublishArtifact;
import com.android.build.gradle.internal.scope.AndroidTask;
import com.android.build.gradle.internal.scope.AndroidTaskRegistry;
import com.android.build.gradle.internal.scope.ConventionMappingHelper;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.TaskConfigAction;
import com.android.build.gradle.internal.scope.VariantOutputScope;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.FileSupplier;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.internal.variant.BaseVariantOutputData;
import com.android.build.gradle.internal.variant.LibraryVariantData;
import com.android.build.gradle.tasks.ManifestProcessorTask;
import com.android.build.gradle.tasks.MergeResources;
import com.android.build.gradle.tasks.MergeSourceSetFolders;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.dependency.DependencyContainer;
import com.android.builder.dependency.DependencyContainerImpl;
import com.android.builder.dependency.JarDependency;
import com.android.builder.dependency.LibraryDependency;
import com.android.builder.dependency.MavenCoordinatesImpl;
import com.android.builder.model.AndroidLibrary;
import com.android.builder.model.JavaLibrary;
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
import com.house365.build.gradle.tasks.MergeShaderManifestsConfigAction;
import groovy.lang.GroovyObject;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodInvokeUtils;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.internal.ConventionMapping;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.reflect.Instantiator;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static com.android.build.gradle.internal.TaskManager.DIR_BUNDLES;

/**
 * Shade task manager for creating tasks in an Android library project.
 * <p/>
 * Created by ZhangZhenli on 2016/3/29.
 */
public class ShadeTaskManager {

    private static final boolean DEBUG = true;
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
    private TaskFactory tasks;
    private final Instantiator instantiator;

    private final AndroidTaskRegistry androidTasks = new AndroidTaskRegistry();

    protected static Logger logger = Logging.getLogger(ShadeTaskManager.class);

    private final Map<String, List<JavaLibrary>> variantShadeJars = new LinkedHashMap<>();
    private final Map<String, List<LibraryDependency>> variantShadeLibraries = new LinkedHashMap<>();

    public List<JavaLibrary> getVariantShadeJars(String variantName) {
        return variantShadeJars.get(variantName);
    }

    public List<LibraryDependency> getVariantShadeLibraries(String variantName) {
        return variantShadeLibraries.get(variantName);
    }

    public ShadeTaskManager(Project project, TaskFactory tasks, Instantiator instantiator, BasePlugin basePlugin, BaseExtension baseExtension) throws IllegalAccessException {
        this.project = project;
        this.tasks = tasks;
        this.instantiator = instantiator;
        this.basePlugin = basePlugin;
        this.android = baseExtension;
        try {
            this.androidBuilder = (AndroidBuilder) FieldUtils.readField(android, "androidBuilder", true);
            this.sdkHandler = (SdkHandler) FieldUtils.readField(android, "sdkHandler", true);
            this.taskManager = (TaskManager) FieldUtils.readField(basePlugin, "taskManager", true);
        } catch (IllegalAccessException e) {
            throw e;
        }
        this.buildTypes = android.getBuildTypes();
        this.productFlavors = android.getProductFlavors();
        this.signingConfigs = android.getSigningConfigs();
        this.isLibrary = android instanceof LibraryExtension ? true : false;


    }


    public void createTasksForVariantData(TaskManager taskManager, LibraryVariantData variantData) throws IllegalAccessException {
        System.out.println("ShadeTaskManager.createTasksForVariantData");
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
        List<LibraryDependency> shadeLibraries = findShadeLibraries(mavenCoordinates, variantData);

        List<LibraryDependency2> shadeLibraries2 = shadeLibraries.stream()
                .map(it -> {
                    LibraryDependency2 dependency2 = new LibraryDependency2(it);
                    return dependency2;
                })
                .collect(Collectors.toList());

        variantShadeLibraries.put(variantData.getName(), shadeLibraries);
        List<JavaLibrary> shadeJars = findShadeJars(mavenCoordinates, variantData);
        variantShadeJars.put(variantData.getName(), shadeJars);

        if (DEBUG) {
            for (LibraryDependency dependency : shadeLibraries) {
                System.out.println(dependency);
            }
        }

        // Shade合并Jar及其关联依赖以及Shade的AAR的关联Jar依赖至本地依赖.
        DependencyContainer originalCompileDependencies = variantData.getVariantDependency().getCompileDependencies();
        DependencyContainer originalPackageDependencies = variantData.getVariantDependency().getPackageDependencies();

        List<AndroidLibrary> androidLibraries = new LinkedList<>(originalCompileDependencies.getAndroidDependencies()).stream()
                .filter(it ->
                        !shadeLibraries.contains(it))
                .collect(Collectors.toList());
        androidLibraries.addAll(shadeLibraries2);
        List<JavaLibrary> javaLibraries = new LinkedList<>(originalCompileDependencies.getJarDependencies()).stream()
                .filter(it ->
                        !shadeJars.contains(it))
                .collect(Collectors.toList());
        shadeJars.stream().forEach(it -> {
            try {
                Field field = FieldUtils.getField(it.getClass(), "mIsProvided", true);
                field.set(it, false);
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
            it.isProvided();
        });
        LinkedList<JavaLibrary> newLocalJars = new LinkedList<>(originalCompileDependencies.getLocalDependencies());
        newLocalJars.addAll(shadeJars);
        // 使用Shade处理后的值替换原有值.
        DependencyContainerImpl compileDependencies = new DependencyContainerImpl(androidLibraries, javaLibraries, newLocalJars);

        List<AndroidLibrary> packageAndroidLibraries = new ArrayList<>(originalPackageDependencies.getAndroidDependencies());
        packageAndroidLibraries.addAll(shadeLibraries);
        List<JavaLibrary> packageLocalJars = new ArrayList<>(originalPackageDependencies.getLocalDependencies());
        packageLocalJars.addAll(shadeJars);

        DependencyContainerImpl packageDependencies = new DependencyContainerImpl(
                packageAndroidLibraries,
                originalPackageDependencies.getJarDependencies(),
                packageLocalJars);
        variantData.getVariantDependency().setDependencies(compileDependencies, packageDependencies);
        variantData.getVariantConfiguration().setDependencies(compileDependencies, packageDependencies);
        try {
            String fieldName;
            fieldName = "mFlatPackageDependencies";
            fixVariantDependencies(variantData, shadeLibraries, shadeJars, fieldName);
            fieldName = "mFlatCompileDependencies";
            fixVariantDependencies(variantData, shadeLibraries, shadeJars, fieldName);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        if (shadeLibraries != null && shadeLibraries.size() > 0) {
            // Generate AAR R.java
            for (LibraryDependency dependency : shadeLibraries) {
                Field field = FieldUtils.getField(dependency.getClass(), "mIsProvided", true);
                field.set(dependency, false);
            }

            appendShadeResourcesTask(variantData, shadeLibraries);

            appendShadeAssetsTask(variantData, shadeLibraries);

            createMergeLibraryManifestsTask(tasks, variantData.getScope());
        }
    }

    private void fixVariantDependencies(LibraryVariantData variantData, List<LibraryDependency> shadeLibraries, List<JavaLibrary> shadeJars, String fieldName) throws IllegalAccessException {
        Field field = FieldUtils.getField(variantData.getVariantConfiguration().getClass(), fieldName, true);
        DependencyContainer mFlatPackageDependencies = (DependencyContainer) field.get(variantData.getVariantConfiguration());
        List<JavaLibrary> flatJavaLibraries = new LinkedList<>(mFlatPackageDependencies.getJarDependencies()).stream()
                .filter(it ->
                        !shadeJars.contains(it))
                .collect(Collectors.toList());
        Field mJavaDependencies = FieldUtils.getField(mFlatPackageDependencies.getClass(), "mJavaDependencies", true);
        mJavaDependencies.set(mFlatPackageDependencies, ImmutableList.copyOf(flatJavaLibraries));

        List<AndroidLibrary> androidLibraries = new LinkedList<>(mFlatPackageDependencies.getAndroidDependencies()).stream()
                .map(it -> (LibraryDependency) it)
                .map(it -> {
                    if (!shadeLibraries.contains(it)) {
                        return it;
                    } else {
                        LibraryDependency2 dependency2 = new LibraryDependency2(it);
                        return dependency2;
                    }
                }).collect(Collectors.toList());
        Field mLibraryDependencies = FieldUtils.getField(mFlatPackageDependencies.getClass(), "mLibraryDependencies", true);
        mLibraryDependencies.set(mFlatPackageDependencies, ImmutableList.copyOf(androidLibraries));
    }

    /**
     * 将AAR中的Resource附加现有的任务参数中.
     *
     * @param variantData
     * @param libraryDependencies
     */
    public void appendShadeResourcesTask(
            LibraryVariantData variantData,
            List<LibraryDependency> libraryDependencies) {

        logger.info("ShadeTaskManager.appendShadeResourcesTask");
        final String taskName = variantData.getScope().getTaskName("package", "Resources");
        final MergeResources mergeResourcesTask = (MergeResources) tasks.named(taskName);

        final LinkedHashSet<ResourceSet> resourceSets = Sets.newLinkedHashSet();
        final boolean validateEnabled = AndroidGradleOptions.isResourceValidationEnabled(
                variantData.getScope().getGlobalScope().getProject());
        for (int n = libraryDependencies.size() - 1; n >= 0; n--) {
            LibraryDependency dependency = libraryDependencies.get(n);
            logger.info("ResFolder: " + dependency.getResFolder());
            File resFolder = dependency.getResFolder();
            if (!resFolder.isFile()) {
                ResourceSet resourceSet =
                        new ResourceSet(dependency.getFolder().getName(), validateEnabled);
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
            List<LibraryDependency> libraryDependencies) {

        logger.info("ShadeTaskManager.appendShadeAssetsTask");
        MergeSourceSetFolders mergeAssetsTask = variantData.mergeAssetsTask;

        final LinkedHashSet<AssetSet> assetSets = Sets.newLinkedHashSet();
        for (int n = libraryDependencies.size() - 1; n >= 0; n--) {
            LibraryDependency dependency = libraryDependencies.get(n);
            File assetFolder = dependency.getAssetsFolder();
            logger.info("AssetFolder: " + assetFolder);
            if (!assetFolder.isFile()) {
                AssetSet assetSet = new AssetSet(dependency.getFolder().getName());
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
        System.out.println("ShadeTaskManager.createMergeLibraryManifestsTask");
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
    private ImmutableList<ManifestMerger2.Invoker.Feature> getFeatures(@NonNull VariantScope variantScope) {
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
     * This artifact is added if the publishNonDefault option is {@code true}.
     * See {@link VariantDependencies#compute variant dependencies evaluation} for more details
     */
    private void addManifestArtifact(
            @NonNull TaskFactory tasks,
            @NonNull BaseVariantData<? extends BaseVariantOutputData> variantData) {
        if (variantData.getVariantDependency().getManifestConfiguration() != null) {
            ManifestProcessorTask mergeManifestsTask =
                    variantData.getOutputs().get(0).getScope().getManifestProcessorTask()
                            .get(tasks);
            project.getArtifacts().add(
                    variantData.getVariantDependency().getManifestConfiguration().getName(),
                    new ManifestPublishArtifact(
                            taskManager.getGlobalScope().getProjectBaseName(),
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
        return new MergeShaderManifestsConfigAction(scope, optionalFeatures);
    }

    /**
     * 从mavenCoordinates中查找当前variant需要shade的所有aar.
     *
     * @param mavenCoordinates
     * @param libraryVariantData
     * @return
     */
    private static List<LibraryDependency> findShadeLibraries(
            Set<MavenCoordinates> mavenCoordinates,
            LibraryVariantData libraryVariantData) {
        List<AndroidLibrary> androidLibraries = libraryVariantData.getVariantConfiguration().getCompileAndroidLibraries();
        LinkedList<LibraryDependency> combinedLibraries = new LinkedList();
        for (AndroidLibrary library : androidLibraries) {
            List<MavenCoordinatesImpl> collect = mavenCoordinates.stream()
                    .map(it ->
                            (MavenCoordinatesImpl) it)
                    .filter(it ->
                            it.compareWithoutVersion(library.getResolvedCoordinates()))
                    .collect(Collectors.toList());
            if (collect.size() > 0) {
                if (library instanceof LibraryDependency2) {
                    combinedLibraries.add(((LibraryDependency2) library).getOriginal());
                } else if (library instanceof LibraryDependency) {
                    combinedLibraries.add((LibraryDependency) library);
                } else {
                    System.out.println("error type " + library);
                }
            }
        }
        return combinedLibraries;
    }


    /**
     * 从mavenCoordinates中查找当前variant需要shade的所有jars(包含直接/间接以及AAR依赖的jar).
     *
     * @param mavenCoordinates
     * @param variantData
     * @return
     */
    private static List<JavaLibrary> findShadeJars(
            Set<MavenCoordinates> mavenCoordinates,
            LibraryVariantData variantData) {
        // Shade合并Jar及其关联依赖以及Shade的AAR的关联Jar依赖至本地依赖.
        DependencyContainer originalCompileDependencies = variantData.getVariantDependency().getCompileDependencies();
        LinkedList<AndroidLibrary> newLibraryDependencies = new LinkedList<>(originalCompileDependencies.getAndroidDependencies());
        LinkedList<JavaLibrary> newJavaDependencies = new LinkedList<>(originalCompileDependencies.getJarDependencies());

        List<JavaLibrary> javaJars = flatNeedShadeJavaDependencies(mavenCoordinates, newJavaDependencies);
        List<JavaLibrary> libraryJars = flatNeedShadeLibraryDependencies(mavenCoordinates, newLibraryDependencies);
        List<JavaLibrary> arrayList = new ArrayList(javaJars);
        arrayList.addAll(libraryJars);
        return arrayList;
    }


    /**
     * 将newJavaDependencies中需要Shade的Jar依赖(包含其本地依赖以及Maven依赖以及关联依赖)提取出来并返回.
     *
     * @param mavenCoordinates
     * @param newJavaDependencies
     */
    private static List<JavaLibrary> flatNeedShadeJavaDependencies(
            Set<MavenCoordinates> mavenCoordinates,
            LinkedList<? extends JavaLibrary> newJavaDependencies) {
        List<JavaLibrary> shadeJars = new ArrayList<>();
        for (int i = 0; i < newJavaDependencies.size(); i++) {
            JavaLibrary javaLibrary = newJavaDependencies.get(i);
            List<MavenCoordinatesImpl> collect = mavenCoordinates.stream()
                    .map(it ->
                            (MavenCoordinatesImpl) it)
                    .filter(it ->
                            it.compareWithoutVersion(javaLibrary.getResolvedCoordinates()))
                    .collect(Collectors.toList());
            if (collect.size() > 0) {
                flatJavaLibrary(javaLibrary, shadeJars);
            }
        }
        return shadeJars;
    }


    /**
     * 将newLibraryDependencies中需要Shade的AAR的Jar依赖(包含其本地依赖以及Maven依赖以及关联依赖)提取出来并放至newLocalJars.
     *
     * @param mavenCoordinates
     * @param newLibraryDependencies
     */
    private static List<JavaLibrary> flatNeedShadeLibraryDependencies(
            Set<MavenCoordinates> mavenCoordinates, List<? extends AndroidLibrary> newLibraryDependencies) {
        List<JavaLibrary> shadeJars = new ArrayList<>();
        for (int i = 0; i < newLibraryDependencies.size(); i++) {
            AndroidLibrary androidLibrary = newLibraryDependencies.get(i);
            List<MavenCoordinatesImpl> coordinates = mavenCoordinates.stream()
                    .map(it ->
                            (MavenCoordinatesImpl) it)
                    .filter(it ->
                            it.compareWithoutVersion(androidLibrary.getResolvedCoordinates()))
                    .collect(Collectors.toList());
            if (coordinates.size() > 0) {
                shadeJars.addAll(flatNeedShadeLibraryDependencies(mavenCoordinates, new LinkedList<AndroidLibrary>(androidLibrary.getLibraryDependencies())));
                for (JavaLibrary it : androidLibrary.getJavaDependencies()) {
                    flatJavaLibrary(it, shadeJars);
                }
                for (File localJarFile : androidLibrary.getLocalJars()) {
                    MavenCoordinates coord = JarDependency.getCoordForLocalJar(localJarFile);
                    shadeJars.add(new JarDependency(
                            localJarFile,
                            ImmutableList.of(),
                            coord,
                            null,
                            false));
                }
            }
        }
        return shadeJars;
    }

    /**
     * 将javaLibrary中的Jar依赖及其关联依赖展开并放至javaLibraries
     *
     * @param javaLibrary
     * @param javaLibraries
     * @return
     */
    private static List<? extends JavaLibrary> flatJavaLibrary(
            JavaLibrary javaLibrary,
            List<JavaLibrary> javaLibraries) {
        javaLibraries.add(javaLibrary);
        if (javaLibrary.getDependencies().size() > 0) {
            for (JavaLibrary it : javaLibrary.getDependencies()) {
                flatJavaLibrary(it, javaLibraries);
            }
        }
        return javaLibraries;
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

    public static class LibraryDependency2 extends LibraryDependency {

        private LibraryDependency dependency;

        LibraryDependency2(LibraryDependency dependencyImpl) {
            super(
                    dependencyImpl.getBundle(),
                    dependencyImpl.getFolder(),
                    new ArrayList(dependencyImpl.getLibraryDependencies()),
                    new ArrayList(dependencyImpl.getJavaDependencies()),
                    dependencyImpl.getName(),
                    dependencyImpl.getProjectVariant(),
                    dependencyImpl.getProject(),
                    dependencyImpl.getRequestedCoordinates(),
                    dependencyImpl.getResolvedCoordinates(),
                    dependencyImpl.isProvided()
            );
            this.dependency = dependencyImpl;
        }

        LibraryDependency getOriginal() {
            return dependency;
        }

        @Override
        public File getJarFile() {
            return new File(this.getJarsRootFolder(), "none");
        }

        @Override
        public List<File> getLocalJars() {
            return Lists.newArrayList();
        }

        @Override
        public boolean isProvided() {
            return false;
        }

        @Override
        public String toString() {
            return super.toString();
        }
    }

}
