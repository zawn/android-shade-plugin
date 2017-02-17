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
import com.android.builder.dependency.LibraryDependency;
import com.android.ide.common.res2.AssetSet;
import com.android.ide.common.res2.ResourceSet;
import com.android.manifmerger.ManifestMerger2;
import com.android.utils.FileUtils;
import com.android.utils.StringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.house365.build.gradle.tasks.MergeShaderManifestsConfigAction;
import com.house365.build.transform.ShadeJarTransform;
import groovy.lang.GroovyObject;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodInvokeUtils;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.internal.ConventionMapping;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.reflect.Instantiator;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;

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


        // 配置AAR合并.
        LinkedHashSet<File> linkedHashSet = ShadeJarTransform.getNeedCombineFiles(project, variantData);

        List<LibraryDependency> libraryDependencies = ShadeJarTransform.getNeedCombineAar(variantData, linkedHashSet);
        if (DEBUG) {
            for (LibraryDependency dependency : libraryDependencies) {
                System.out.println(dependency);
            }
        }

        if (libraryDependencies != null && libraryDependencies.size() > 0) {
            // Generate AAR R.java
            for (LibraryDependency dependency : libraryDependencies) {
                Field field = FieldUtils.getField(dependency.getClass(), "mIsProvided", true);
                field.set(dependency, false);
            }

            appendShadeResourcesTask(variantData, libraryDependencies);

            appendShadeAssetsTask(variantData, libraryDependencies);

            createMergeLibraryManifestsTask(tasks, variantData.getScope());

        }


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

   /* private void mergerShadeManifestTask(LibraryVariantData variantData, List<LibraryDependency> libraryDependencies) {
        VariantScope variantScope = variantData.getScope();
        BaseVariantOutputData variantOutputData = variantScope.getVariantData().getOutputs().get(0);
        Task proecssorTask = project.getTasks().findByName(variantOutputData.manifestProcessorTask.getName());
//          proecssorTask.deleteAllActions()
//        proecssorTask.finalizedBy(libManifestMergeTask);
    }*/

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

}
