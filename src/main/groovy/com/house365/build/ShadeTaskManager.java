package com.house365.build;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import android.databinding.tool.DataBindingBuilder;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodInvokeUtils;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.specs.CompositeSpec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.internal.component.local.model.PublishArtifactLocalArtifactMetadata;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;
import org.jetbrains.annotations.NotNull;

import com.android.annotations.NonNull;
import com.android.annotations.VisibleForTesting;
import com.android.build.api.attributes.BuildTypeAttr;
import com.android.build.api.transform.Context;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Transform;
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
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType;
import com.android.build.gradle.internal.scope.AndroidTask;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.transforms.LibraryBaseTransform;
import com.android.build.gradle.internal.transforms.LibraryJniLibsTransform;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.options.BooleanOption;
import com.android.build.gradle.options.ProjectOptions;
import com.android.build.gradle.tasks.ManifestProcessorTask;
import com.android.build.gradle.tasks.MergeResources;
import com.android.build.gradle.tasks.MergeSourceSetFolders;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.model.SourceProvider;
import com.android.builder.profile.Recorder;
import com.android.manifmerger.ManifestMerger2;
import com.android.utils.FileUtils;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.house365.build.gradle.tasks.MergeManifests;
import com.house365.build.gradle.tasks.ShadeJniLibsAction;
import com.house365.build.transform.LibraryAarJarsTransform;

import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.AIDL;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.ANDROID_RES;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.ASSETS;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.CLASSES;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.JNI;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.MANIFEST;
import static com.android.build.gradle.internal.scope.TaskOutputHolder.TaskOutputType.INSTANT_RUN_MERGED_MANIFESTS;
import static com.android.utils.StringHelper.capitalize;

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
        ConfigurationContainer configurations = project.getConfigurations();
        Configuration runtimeShadeClasspath = configurations.maybeCreate(variantName + "RuntimeShadeClasspath");
        applyVariantAttributes(variantScope, runtimeShadeClasspath);

        Configuration shadeClasspath = configurations.maybeCreate(variantName + "ShadeClasspath");
        applyVariantAttributes(variantScope, shadeClasspath);

        Configuration runtimeClasspath = configurations.maybeCreate(variantName + "RuntimeClasspath");
        runtimeShadeClasspath.getDependencies().addAll(runtimeClasspath.getAllDependencies());

        HashSet<Configuration> hashSet = ShadePlugin.instance.extension.getConfigurationAndExtends(variantName + "Shade");

        if (hashSet != null) {
            for (Configuration configuration : hashSet) {
                shadeClasspath.extendsFrom(configuration);
                runtimeShadeClasspath.extendsFrom(configuration);
            }
        }

        Set<ResolvedArtifactResult> artifacts = getArtifactCollection(shadeClasspath, variantScope, CLASSES).getArtifacts();

        Set<ResolvedArtifactResult> artifacts1 = getArtifactCollection(shadeClasspath, variantScope, MANIFEST).getArtifacts();
        Set<ComponentIdentifier> collect = artifacts1.stream().map(t -> t.getId().getComponentIdentifier()).collect(Collectors.toSet());

        Set<ResolvedArtifactResult> jarArtifacts = artifacts.stream().filter(t ->
                !collect.contains(t.getId().getComponentIdentifier())
        ).collect(Collectors.toSet());

        Set<ResolvedArtifactResult> classArtifacts = getResolvedArtifactResults(variantScope, runtimeShadeClasspath, jarArtifacts, CLASSES).getArtifacts();

        Set<File> jarFiles = new HashSet<>();

        for (ResolvedArtifactResult runtimeShadeArtifact : classArtifacts) {
            jarFiles.add(runtimeShadeArtifact.getFile());
        }

        Configuration shadeJarClasspath = configurations.maybeCreate(variantName + "ShadeJarClasspath");
        project.getDependencies().add(shadeJarClasspath.getName(), project.files(jarFiles));
        runtimeClasspath.extendsFrom(shadeJarClasspath);
        if (variantScope.getVariantConfiguration().getBuildType().getName().equals("debug")) {
            configurations.getByName(variantName + "AndroidTestRuntimeClasspath").extendsFrom(shadeJarClasspath);
        }
        Configuration compileClasspath = configurations.getByName(variantName + "CompileClasspath");
        compileClasspath.extendsFrom(hashSet.toArray(new Configuration[hashSet.size()]));

        ArtifactCollection aidlArtifacts = getShadeArtifactCollection(variantScope, AIDL);
        if (aidlArtifacts.getArtifacts().size() > 0) {
            throw new GradleException("The current version of the shade plug-in does not support AIDL merge processing.");
        }

        configurations.getByName(variantName + "UnitTestRuntimeClasspath").extendsFrom(shadeJarClasspath);

        AndroidTask<? extends JavaCompile> javacTask = variantScope.getJavacTask();

        // 针对实际配置有shader依赖的Variant创建相应的任务.
        if (hasShadeDependencies(variantData)) {
            try {
                appendShadeResourcesTask(tasks, variantScope);

                appendShadeAssetsTask(tasks, variantScope);

                createSyncShadeJniLibsTransform(tasks, variantScope);

                createMergeApkManifestsTask(tasks, variantData.getScope());

                fixAddLocalJarRes(tasks, variantScope);
            } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                throw new GradleException(e.getMessage());
            }
        }
    }

    private void fixAddLocalJarRes(TaskFactory tasks, VariantScope variantScope) {
        /**
         * {@link com.android.build.gradle.internal.transforms.LibraryAarJarsTransform#transform(TransformInvocation)}实现中丢弃了Local Jar中的Res.
         * 该处理方法在部分情况先会导致程序出错,比如在Local Jar中直接添加okhttp的依赖.故添加对Local Jar中的Res的处理.
         */
        TransformManager transformManager = variantScope.getTransformManager();
        try {
            List<Transform> transforms = (List<Transform>) FieldUtils.readField(transformManager, "transforms", true);
            for (Transform transform : transforms) {
                if (transform instanceof com.android.build.gradle.internal.transforms.LibraryAarJarsTransform) {
                    LibraryAarJarsTransform aarJarsTransform = new LibraryAarJarsTransform((LibraryBaseTransform) transform);
                    String taskName = variantScope.getTaskName(getTaskNamePrefix(transform));
                    TransformTask named = (TransformTask) tasks.named(taskName);
                    FieldUtils.writeField(named, "transform", aarJarsTransform, true);
                }
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    @NotNull
    private void applyVariantAttributes(VariantScope variantScope, Configuration configuration) {
        ObjectFactory factory = project.getObjects();
        final Usage runtimeUsage = factory.named(Usage.class, Usage.JAVA_RUNTIME);
        final AttributeContainer attributeContainer = configuration.getAttributes();
        attributeContainer.attribute(BuildTypeAttr.ATTRIBUTE, project.getObjects().named(BuildTypeAttr.class, variantScope.getVariantConfiguration().getBuildType().getName()));
        attributeContainer.attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage);
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
        ArtifactCollection artifacts = getShadeArtifactCollection(variantScope, ANDROID_RES);
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
        ArtifactCollection artifacts = getShadeArtifactCollection(variantScope, ASSETS);
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
        ArtifactCollection artifacts = getShadeArtifactCollection(variantScope.getFullVariantName(), AndroidArtifacts.ArtifactType.JNI);
        MethodInvokeUtils.invokeMethod(mergeAssetsTask, "setLibraries", artifacts);
*/
        TransformManager transformManager = variantScope.getTransformManager();

        ArtifactCollection aidlArtifacts = getShadeArtifactCollection(variantScope, JNI);
        Set<ResolvedArtifactResult> artifacts = aidlArtifacts.getArtifacts();
        Set<PublishArtifactLocalArtifactMetadata> set = new HashSet<>();
        for (ResolvedArtifactResult artifact : artifacts) {
            ComponentArtifactIdentifier id = artifact.getId();
            if (id instanceof PublishArtifactLocalArtifactMetadata) {
                set.add((PublishArtifactLocalArtifactMetadata) artifact.getId());
            }
        }

        try {
            List<Transform> transforms = (List<Transform>) FieldUtils.readField(transformManager, "transforms", true);
            for (Transform transform : transforms) {
                if (transform instanceof LibraryJniLibsTransform) {
                    String taskName = variantScope.getTaskName(getTaskNamePrefix(transform));
                    File jniLibsFolder = (File) FieldUtils.readField(transform, "jniLibsFolder", true);
                    ShadeJniLibsAction action = new ShadeJniLibsAction(this, variantScope, jniLibsFolder);
                    Task task = tasks.named(taskName);
                    task.doLast(action);
                    if (!set.isEmpty()) {
                        for (PublishArtifactLocalArtifactMetadata id : set) {
                            task.dependsOn(id.getBuildDependencies());
                        }
                    }
                }
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    public ArtifactCollection getShadeArtifactCollection(
            VariantScope variantScope,
            ArtifactType artifactType) {
        return getShadeArtifactCollection(project, variantScope, artifactType);
    }


    /**
     * 参照resolvedArtifactResults,获取configuration中实际使用的依赖集合.
     *
     * @param variantScope
     * @param configuration
     * @param resolvedArtifactResults
     * @param artifactType
     * @return
     */
    @NotNull
    private static ArtifactCollection getResolvedArtifactResults(
            VariantScope variantScope,
            Configuration configuration,
            Set<ResolvedArtifactResult> resolvedArtifactResults,
            ArtifactType artifactType) {
        Set<ComponentIdentifier> componentIdentifiers = new HashSet<>(resolvedArtifactResults.size(), 1);
        Set<String> moduleWithoutVersion = new HashSet<>(resolvedArtifactResults.size(), 1);
        for (ResolvedArtifactResult artifact : resolvedArtifactResults) {
            ComponentIdentifier identifier = artifact.getId().getComponentIdentifier();
            if (identifier instanceof ModuleComponentIdentifier) {
                String group = ((ModuleComponentIdentifier) identifier).getGroup();
                String module = ((ModuleComponentIdentifier) identifier).getModule();
                moduleWithoutVersion.add(group + ":" + module);
            } else {
                componentIdentifiers.add(identifier);
            }
        }
        return getArtifactCollection(configuration, variantScope, artifactType, new CompositeSpec<ComponentIdentifier>() {
            @Override
            public boolean isSatisfiedBy(ComponentIdentifier element) {
                if (element instanceof ModuleComponentIdentifier) {
                    String group = ((ModuleComponentIdentifier) element).getGroup();
                    String module = ((ModuleComponentIdentifier) element).getModule();
                    return moduleWithoutVersion.contains(group + ":" + module);
                } else if (componentIdentifiers.contains(element)) {
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * 功能类似于VariantScopeImpl#getShadeArtifactCollection,用户获取Shade的AAR中的制定资源.
     *
     * @param variantScope
     * @param artifactType
     * @see com.android.build.gradle.internal.scope.VariantScopeImpl#getArtifactCollection(AndroidArtifacts.ConsumedConfigType, AndroidArtifacts.ArtifactScope, ArtifactType) ;
     */
    @NotNull
    public static ArtifactCollection getShadeArtifactCollection(
            Project project,
            VariantScope variantScope,
            ArtifactType artifactType) {
        ConfigurationContainer configurations = project.getConfigurations();
        String variantName = variantScope.getFullVariantName();
        Configuration shadeClasspath = configurations.maybeCreate(variantName + "ShadeClasspath");
        Set<ResolvedArtifactResult> artifacts = getArtifactCollection(shadeClasspath, variantScope, MANIFEST).getArtifacts();
        Configuration runtimeShadeClasspath = configurations.maybeCreate(variantName + "RuntimeShadeClasspath");
        return getResolvedArtifactResults(variantScope, runtimeShadeClasspath, artifacts, artifactType);
    }

    public static ArtifactCollection getArtifactCollection(
            Configuration configuration,
            VariantScope variantScope,
            ArtifactType artifactType) {
        return getArtifactCollection(configuration, variantScope.getGlobalScope(), artifactType, null);
    }

    public static ArtifactCollection getArtifactCollection(
            Configuration configuration,
            VariantScope variantScope,
            ArtifactType artifactType, CompositeSpec<ComponentIdentifier> filter) {
        return getArtifactCollection(configuration, variantScope.getGlobalScope(), artifactType, filter);
    }

    public static ArtifactCollection getArtifactCollection(
            Configuration configuration,
            GlobalScope globalScope,
            ArtifactType artifactType,
            CompositeSpec<ComponentIdentifier> filter) {
        Action<AttributeContainer> attributes =
                container -> container.attribute(AndroidArtifacts.ARTIFACT_TYPE, artifactType.getType());

        boolean lenientMode =
                Boolean.TRUE.equals(
                        globalScope.getProjectOptions().get(BooleanOption.IDE_BUILD_MODEL_ONLY));

        return configuration.getIncoming().artifactView(
                config -> {
                    config.attributes(attributes);
                    if (filter != null)
                        config.componentFilter(filter);
                    config.lenient(lenientMode);
                }).getArtifacts();
    }

    /**
     * @param transform
     * @return
     * @see TransformManager#getTaskNamePrefix(Transform)
     */
    @VisibleForTesting
    @NonNull
    static String getTaskNamePrefix(@NonNull Transform transform) {
        StringBuilder sb = new StringBuilder(100);
        sb.append("transform");

        sb.append(
                transform.getInputTypes()
                        .stream()
                        .map(inputType ->
                                CaseFormat.UPPER_UNDERSCORE.to(
                                        CaseFormat.UPPER_CAMEL, inputType.name()))
                        .sorted() // Keep the order stable.
                        .collect(Collectors.joining("And")))
                .append("With")
                .append(capitalize(transform.getName()))
                .append("For");

        return sb.toString();
    }
}
