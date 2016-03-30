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
import com.android.build.gradle.internal.dependency.ManifestDependencyImpl;
import com.android.build.gradle.internal.dsl.BuildType;
import com.android.build.gradle.internal.dsl.CoreBuildType;
import com.android.build.gradle.internal.dsl.ProductFlavor;
import com.android.build.gradle.internal.dsl.SigningConfig;
import com.android.build.gradle.internal.scope.AndroidTask;
import com.android.build.gradle.internal.scope.AndroidTaskRegistry;
import com.android.build.gradle.internal.scope.ConventionMappingHelper;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.variant.BaseVariantOutputData;
import com.android.build.gradle.internal.variant.LibraryVariantData;
import com.android.build.gradle.tasks.MergeResources;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.dependency.LibraryDependency;
import com.android.ide.common.res2.ResourceSet;
import com.android.utils.FileUtils;
import com.android.utils.StringHelper;
import com.google.common.collect.Lists;
import com.house365.build.task.LibraryManifestMergeTask;
import com.house365.build.transform.ShadeJarTransform;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.reflect.Instantiator;

import java.io.File;
import java.lang.reflect.Field;
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
    private final Instantiator instantiator;

    private final AndroidTaskRegistry androidTasks = new AndroidTaskRegistry();

    protected static Logger logger = Logging.getLogger(ShadeTaskManager.class);


    public ShadeTaskManager(Project project, Instantiator instantiator, BasePlugin basePlugin, BaseExtension baseExtension) throws IllegalAccessException {
        this.project = project;
        this.instantiator = instantiator;
        this.basePlugin = basePlugin;
        this.android = baseExtension;
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


    }


    public void createTasksForVariantData(TaskFactory tasks, TaskManager taskManager, LibraryVariantData variantData) throws IllegalAccessException {
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
        for (File file : linkedHashSet) {
            logger.lifecycle(file.toString());
        }
        logger.lifecycle("\n");
        List<LibraryDependency> libraryDependencies = ShadeJarTransform.getNeedCombineAar(variantData, linkedHashSet);

        if (libraryDependencies != null && libraryDependencies.size() > 0) {
            // Generate AAR R.java
            for (LibraryDependency dependency : libraryDependencies) {
                Field field = FieldUtils.getField(dependency.getClass(), "isOptional", true);
                field.set(dependency, false);
            }

            ShadeJarTransform.addAssetsToBundle(variantData, libraryDependencies);

            createMergeResourcesTask(tasks, variantData, variantScope, variantBundleDir, libraryDependencies);

            mergerAndroidManifest(variantData, variantScope, libraryDependencies);

        }


    }

    public void createMergeResourcesTask(
            @NonNull TaskFactory tasks, LibraryVariantData variantData, VariantScope scope, File variantBundleDir, List<LibraryDependency> libraryDependencies) {
        AndroidTask<MergeResources> mergeResourcesTask = androidTasks.create(tasks,
                new MergeResources.ConfigAction(
                        scope,
                        "mergeShade",
                        FileUtils.join(variantBundleDir, "res"),
                        true,
                        true));
        MergeResources mergeResources = mergeResourcesTask.get(tasks);
        addResourceToBundle(variantData, mergeResources, libraryDependencies);
        tasks.named(mergeResources.getName().replace("mergeShade", "package")).finalizedBy(mergeResources);
    }

    private void mergerAndroidManifest(LibraryVariantData variantData, VariantScope variantScope, List<LibraryDependency> libraryDependencies) {
        // Merge AndroidManifest.xml
        List<ManifestDependencyImpl> libraries = LibraryManifestMergeTask.getManifestDependencies(libraryDependencies);
        LibraryManifestMergeTask libManifestMergeTask = project.getTasks().create(variantScope.getTaskName("process", "ShadeManifest"), LibraryManifestMergeTask.class);
        libManifestMergeTask.variantData = variantData;
        libManifestMergeTask.libraries = libraries;
        BaseVariantOutputData variantOutputData = variantScope.getVariantData().getOutputs().get(0);
        Task proecssorTask = project.getTasks().findByName(variantOutputData.manifestProcessorTask.getName());
//          proecssorTask.deleteAllActions()
        proecssorTask.finalizedBy(libManifestMergeTask);
    }


    /**
     * 将Shade AAR中的Resource合并进bundle.
     *
     * @param variantData
     */
    public
    static void addResourceToBundle(final LibraryVariantData variantData, final MergeResources mergeResources, List<LibraryDependency> libraryDependencies) {
        final boolean validateEnabled = AndroidGradleOptions.isResourceValidationEnabled(
                variantData.getScope().getGlobalScope().getProject());
        final List<ResourceSet> resourceSets = Lists.newArrayList();
        for (int n = libraryDependencies.size() - 1; n >= 0; n--) {
            LibraryDependency dependency = libraryDependencies.get(n);
            if (DEBUG)
                System.out.println("ResFolder: " + dependency.getResFolder());
            File resFolder = dependency.getResFolder();
            if (!resFolder.isFile()) {
                ResourceSet resourceSet =
                        new ResourceSet(dependency.getFolder().getName(), validateEnabled);
                resourceSet.addSource(resFolder);
                resourceSet.setFromDependency(true);
                resourceSets.add(resourceSet);
            }
        }

//        ConventionMapping conventionMapping =
//                (ConventionMapping) ((GroovyObject) mergeResources).getProperty("conventionMapping");
//        resourceSets.addAll(conventionMapping.getConventionValue(new ArrayList<ResourceSet>(), "inputResourceSets", false));

        ConventionMappingHelper.map(mergeResources, "inputResourceSets", new Callable<List<ResourceSet>>() {
            @Override
            public List<ResourceSet> call() throws Exception {
                return resourceSets;
            }
        });

        mergeResources.doLast(new Action<Task>() {
            @Override
            public void execute(Task task) {
                logger.lifecycle(mergeResources.getOutputDir().toString());
                logger.lifecycle("555555555555555555555555555555555566666666666666666666666666666666");
            }
        });

        if (DEBUG) {
            logger.lifecycle("Combined with all the resource");
            for (ResourceSet resourceSet : resourceSets) {
                logger.lifecycle(resourceSet.toString());
            }
            logger.lifecycle("\n");
        }
    }
}
