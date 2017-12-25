package com.house365.build;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import android.databinding.tool.DataBindingBuilder;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.reflect.MethodInvokeUtils;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;
import org.jetbrains.annotations.NotNull;

import com.android.annotations.NonNull;
import com.android.build.api.transform.Context;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.gradle.AndroidConfig;
import com.android.build.gradle.BasePlugin;
import com.android.build.gradle.internal.SdkHandler;
import com.android.build.gradle.internal.TaskFactory;
import com.android.build.gradle.internal.TaskManager;
import com.android.build.gradle.internal.core.GradleVariantConfiguration;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.pipeline.TransformTask;
import com.android.build.gradle.internal.publishing.AndroidArtifacts;
import com.android.build.gradle.internal.scope.AndroidTask;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.options.ProjectOptions;
import com.android.build.gradle.tasks.ManifestProcessorTask;
import com.android.build.gradle.tasks.MergeResources;
import com.android.build.gradle.tasks.MergeSourceSetFolders;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.model.SourceProvider;
import com.android.builder.profile.Recorder;
import com.android.manifmerger.ManifestMerger2;
import com.android.utils.FileUtils;
import com.google.common.collect.ImmutableList;
import com.house365.build.gradle.tasks.MergeManifests;
import com.house365.build.transform.ShadeJniLibsTransform;

import static com.android.build.gradle.internal.scope.TaskOutputHolder.TaskOutputType.INSTANT_RUN_MERGED_MANIFESTS;

/**
 * Shade 任务管理.
 */
public class ShadeTaskManager extends TaskManager {

    private static final Logger logger = Logger.getLogger("ShadeTaskManager");
    private final BasePlugin basePlugin;

    public ShadeTaskManager(
            BasePlugin basePlugin,
            GlobalScope globalScope,
            Project project,
            ProjectOptions projectOptions,
            AndroidBuilder androidBuilder,
            DataBindingBuilder dataBindingBuilder,
            AndroidConfig extension,
            SdkHandler sdkHandler,
            ToolingModelBuilderRegistry toolingRegistry,
            Recorder recorder) {
        super(globalScope, project, projectOptions, androidBuilder, dataBindingBuilder, extension, sdkHandler, toolingRegistry, recorder);
        this.basePlugin = basePlugin;
    }

    @Override
    public void createTasksForVariantScope(TaskFactory tasks, VariantScope variantScope) {
        System.out.println("ShadeTaskManager.createTasksForVariantScope");
        BaseVariantData variantData = variantScope.getVariantData();

        // 将shader configurations 加入到javac编译依赖中.
        String variantName = variantScope.getVariantConfiguration().getFullName();

        /**
         * 将maven模块以来转换为本地file直接依赖
         */
        Configuration runtimeClasspath = project.getConfigurations().maybeCreate(variantName + "RuntimeClasspath");
        Configuration runtimeShadeClasspath = project.getConfigurations().maybeCreate(variantName + "RuntimeShadeClasspath");
        runtimeShadeClasspath.getDependencies().addAll(runtimeClasspath.getAllDependencies());

        HashSet<Configuration> configurations = ShadePlugin.instance.extension.getConfigurationAndExtends(variantName + "Shade");
        if (configurations != null) {
            for (Configuration configuration : configurations) {
                runtimeShadeClasspath.extendsFrom(configuration);
            }
        }

        HashSet<Dependency> requestedSet = new HashSet<>();
        for (Configuration configuration : configurations) {
            for (Iterator<Dependency> iterator = configuration.getAllDependencies().iterator(); iterator.hasNext(); ) {
                Dependency dependency = iterator.next();
                requestedSet.add(dependency);
            }
        }

        Set<File> files = runtimeShadeClasspath.files(new Spec<Dependency>() {
            @Override
            public boolean isSatisfiedBy(Dependency element) {
                if (requestedSet.contains(element)) {
                    return true;
                }
                return false;
            }
        });
        Set<File> jarFiles = new HashSet<>();
        Set<File> aarFiles = new HashSet<>();
        for (File file : files) {
            if (FilenameUtils.getExtension(file.getName()).equalsIgnoreCase("jar")) {
                jarFiles.add(file);
            } else {
                aarFiles.add(file);
            }
        }

        Configuration shadeJarClasspath = project.getConfigurations().maybeCreate(variantName + "ShadeJarClasspath");
        project.getDependencies().add(shadeJarClasspath.getName(), project.files(jarFiles));
        runtimeClasspath.extendsFrom(shadeJarClasspath);
        if (variantScope.getVariantConfiguration().getBuildType().getName().equals("debug")) {
            project.getConfigurations().getByName(variantName + "AndroidTestRuntimeClasspath").extendsFrom(shadeJarClasspath);
        }
        Configuration compileClasspath = project.getConfigurations().getByName(variantName + "CompileClasspath");
        compileClasspath.extendsFrom(configurations.toArray(new Configuration[configurations.size()]));
        Configuration shadeAarClasspath = project.getConfigurations().maybeCreate(variantName + "ShadeAarClasspath");
        if (aarFiles.size() > 0) {
            project.getDependencies().add(shadeAarClasspath.getName(), project.files(aarFiles));
        }
        for (Configuration configuration : configurations) {
            runtimeClasspath.extendsFrom(configuration);
        }
        ArtifactCollection aidlArtifacts = getArtifactCollection(variantName, AndroidArtifacts.ArtifactType.AIDL);
        if (aidlArtifacts.getArtifacts().size() > 0) {
            throw new GradleException("The current version of the shade plug-in does not support AIDL merge processing.");
        }

        project.getConfigurations().getByName(variantName + "UnitTestRuntimeClasspath").extendsFrom(shadeJarClasspath);

        AndroidTask<? extends JavaCompile> javacTask = variantScope.getJavacTask();

        // 针对实际配置有shader依赖的Variant创建相应的任务.
        if (hasShadeDependencies(variantData)) {
            try {
                appendShadeResourcesTask(tasks, variantScope);

                appendShadeAssetsTask(tasks, variantScope);

                createSyncShadeJniLibsTransform(tasks, variantScope);

                createMergeApkManifestsTask(tasks, variantData.getScope());
            } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                throw new GradleException(e.getMessage());
            }
        }
    }


    /**
     * 检查是否有配置shadow依赖.
     *
     * @param variantData
     * @return
     */
    private boolean hasShadeDependencies(BaseVariantData variantData) {
        boolean hasShadeDependencies = false;
        Set<Configuration> shadeConfigurations = getVariantShadeConfigurations(project.getConfigurations(), variantData.getVariantConfiguration());
        for (Configuration shadeConfiguration : shadeConfigurations) {
            if (shadeConfiguration.getDependencies().size() > 0) {
                hasShadeDependencies = true;
            }
        }
        return hasShadeDependencies;
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


    @Override
    protected Set<? super QualifiedContent.Scope> getResMergingScopes(VariantScope variantScope) {
        throw new UnsupportedOperationException("getResMergingScopes");
    }

    @Override
    protected void postJavacCreation(TaskFactory tasks, VariantScope scope) {
        throw new UnsupportedOperationException("postJavacCreation");
    }

    public VariantScope getCurrentVariant(TransformInvocation invocation) {
        Context context = invocation.getContext();
        for (VariantScope variantScope : basePlugin.getVariantManager().getVariantScopes()) {
            if (variantScope.getFullVariantName().equals(context.getVariantName())) {
                return variantScope;
            }
        }
        return null;
    }

    /**
     * 将AAR中的Resource附加现有的任务参数中.
     *
     * @param tasks
     * @param variantScope
     */
    private void appendShadeResourcesTask(
            TaskFactory tasks,
            VariantScope variantScope) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        logger.log(Level.INFO, "appendShadeResourcesTask() called with: tasks = [" + tasks + "], variantScope = [" + variantScope + "]");
        ArtifactCollection artifacts = getArtifactCollection(variantScope.getFullVariantName(), AndroidArtifacts.ArtifactType.ANDROID_RES);
        MergeResources packageDebugResources = (MergeResources) tasks.named(variantScope.getTaskName("package", "Resources"));
        MethodInvokeUtils.invokeMethod(packageDebugResources, "setLibraries", artifacts);
    }

    /**
     * 将Shade AAR中的Asset合并进bundle.
     *
     * @param tasks
     * @param variantScope
     */
    private void appendShadeAssetsTask(
            TaskFactory tasks,
            VariantScope variantScope) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        logger.log(Level.INFO, "appendShadeAssetsTask() called with: tasks = [" + tasks + "], variantScope = [" + variantScope + "]");
        MergeSourceSetFolders mergeAssetsTask = variantScope.getVariantData().mergeAssetsTask;
        ArtifactCollection artifacts = getArtifactCollection(variantScope.getFullVariantName(), AndroidArtifacts.ArtifactType.ASSETS);
        MethodInvokeUtils.invokeMethod(mergeAssetsTask, "setLibraries", artifacts);
    }

    /**
     * 创建合并Android Manifest文件的Task,参考:{@link TaskManager#createMergeApkManifestsTask(TaskFactory, VariantScope)}
     *
     * @param tasks
     * @param variantScope
     */

    public void createMergeApkManifestsTask(
            @NonNull TaskFactory tasks, @NonNull VariantScope variantScope) {

        ImmutableList.Builder<ManifestMerger2.Invoker.Feature> optionalFeatures =
                ImmutableList.builder();

        if (variantScope.isTestOnly()) {
            optionalFeatures.add(ManifestMerger2.Invoker.Feature.TEST_ONLY);
        }

        if (variantScope.getVariantConfiguration().getBuildType().isDebuggable()) {
            optionalFeatures.add(ManifestMerger2.Invoker.Feature.DEBUGGABLE);
        }

//        if (!getAdvancedProfilingTransforms(projectOptions).isEmpty()
//                && variantScope.getVariantConfiguration().getBuildType().isDebuggable()) {
//            optionalFeatures.add(ManifestMerger2.Invoker.Feature.ADVANCED_PROFILING);
//        }

        AndroidTask<? extends ManifestProcessorTask> processManifestTask =
                createMergeManifestTask(tasks, variantScope, optionalFeatures);

//        variantScope.addTaskOutput(
//                MERGED_MANIFESTS,
//                variantScope.getManifestOutputDirectory(),
//                processManifestTask.getName());

//        variantScope.addTaskOutput(
//                TaskOutputHolder.TaskOutputType.MANIFEST_METADATA,
//                BuildOutputs.getMetadataFile(variantScope.getManifestOutputDirectory()),
//                processManifestTask.getName());

        // TODO: use FileCollection
        tasks.named(variantScope.getManifestProcessorTask().getName()).finalizedBy(processManifestTask.getName());
        variantScope.setManifestProcessorTask(processManifestTask);

        processManifestTask.dependsOn(tasks, variantScope.getCheckManifestTask());

        if (variantScope.getMicroApkTask() != null) {
            processManifestTask.dependsOn(tasks, variantScope.getMicroApkTask());
        }
    }

    /**
     * Creates the merge manifests task.
     */
    @NonNull
    protected AndroidTask<? extends ManifestProcessorTask> createMergeManifestTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope variantScope,
            @NonNull ImmutableList.Builder<ManifestMerger2.Invoker.Feature> optionalFeatures) {
        if (variantScope.getVariantConfiguration().isInstantRunBuild(globalScope)) {
            optionalFeatures.add(ManifestMerger2.Invoker.Feature.INSTANT_RUN_REPLACEMENT);
        }

        final File reportFile = computeManifestReportFile(variantScope);
        AndroidTask<MergeManifests> mergeManifestsAndroidTask =
                androidTasks.create(
                        tasks,
                        new MergeManifests.ConfigAction(
                                variantScope, optionalFeatures.build(), reportFile));

        final String name = mergeManifestsAndroidTask.getName();

        variantScope.addTaskOutput(
                INSTANT_RUN_MERGED_MANIFESTS,
                variantScope.getInstantRunManifestOutputDirectory(),
                name);

//        variantScope.addTaskOutput(MANIFEST_MERGE_REPORT, reportFile, name);

        return mergeManifestsAndroidTask;
    }

    @NonNull
    private static File computeManifestReportFile(@NonNull VariantScope variantScope) {
        return FileUtils.join(
                variantScope.getGlobalScope().getOutputsDir(),
                "logs",
                "manifest-merger-"
                        + variantScope.getVariantConfiguration().getBaseName()
                        + "-report.txt");
    }

    /**
     * 将Shade AAR中的Jni合并进bundle.
     *
     * @param tasks
     * @param variantScope
     */
    private void createSyncShadeJniLibsTransform(
            TaskFactory tasks,
            VariantScope variantScope) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
/*
        // 该处理逻辑功能可以实现,但是与Jar中的so处理时机LibraryJniLibsTransform(syncJniLibs)不一致,废弃.
        logger.log(Level.INFO, "appendShadeAssetsTask() called with: tasks = [" + tasks + "], variantScope = [" + variantScope + "]");
        MergeSourceSetFolders mergeAssetsTask = (MergeSourceSetFolders) tasks.named(variantScope.getTaskName("merge", "JniLibFolders"));
        ArtifactCollection artifacts = getArtifactCollection(variantScope.getFullVariantName(), AndroidArtifacts.ArtifactType.JNI);
        MethodInvokeUtils.invokeMethod(mergeAssetsTask, "setLibraries", artifacts);
*/

        TransformManager transformManager = variantScope.getTransformManager();
        Optional<AndroidTask<TransformTask>> taskOptional
                = transformManager.addTransform(tasks, variantScope, new ShadeJniLibsTransform(this, variantScope));
        AndroidTask<TransformTask> shadeJniLibsAndroidTask = taskOptional.get();
        AndroidTask<TransformTask> syncJniLibsAndroidTask = transformManager.getTaskRegistry().get(shadeJniLibsAndroidTask.getName().replace("ShadeJniLibs", "SyncJniLibs"));
        shadeJniLibsAndroidTask.dependsOn(tasks, syncJniLibsAndroidTask);

        Task bundle = project.getTasks().findByName(variantScope.getTaskName("bundle"));
        taskOptional.ifPresent(t -> bundle.dependsOn(t.getName()));
    }

    public ArtifactCollection getArtifactCollection(
            String variantName,
            AndroidArtifacts.ArtifactType artifactType) {
        return getArtifactCollection(project, variantName, artifactType);
    }

    /**
     * 功能类似于VariantScopeImpl#getArtifactCollection,用户获取Shade的AAR中的制定资源.
     *
     * @param variantName
     * @param artifactType
     * @return
     * @see com.android.build.gradle.internal.scope.VariantScopeImpl#getArtifactCollection(AndroidArtifacts.ConsumedConfigType, AndroidArtifacts.ArtifactScope, AndroidArtifacts.ArtifactType) ;
     */
    @NotNull
    public static ArtifactCollection getArtifactCollection(
            Project project,
            String variantName,
            AndroidArtifacts.ArtifactType artifactType) {
        Configuration shadeAarClasspath = project.getConfigurations().maybeCreate(variantName + "ShadeAarClasspath");
        Action<AttributeContainer> attributes =
                container -> container.attribute(AndroidArtifacts.ARTIFACT_TYPE, artifactType.getType());
        return shadeAarClasspath.getIncoming().artifactView(
                config -> {
                    config.attributes(attributes);
                }).getArtifacts();
    }
}
