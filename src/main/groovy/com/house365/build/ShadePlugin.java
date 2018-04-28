package com.house365.build;

import javax.inject.Inject;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.ProjectConfigurationException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import com.android.build.gradle.AppPlugin;
import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.BasePlugin;
import com.android.build.gradle.LibraryExtension;
import com.android.build.gradle.LibraryPlugin;
import com.android.build.gradle.internal.BadPluginException;
import com.android.build.gradle.internal.TaskContainerAdaptor;
import com.android.build.gradle.internal.TaskFactory;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.internal.variant.LibraryVariantData;
import com.android.builder.model.Version;
import com.android.builder.profile.Recorder;
import com.android.builder.profile.ThreadRecorder;
import com.google.wireless.android.sdk.stats.GradleBuildProfileSpan.ExecutionType;
import com.house365.build.task.ShadeTask;
import com.house365.build.transform.ShadeAarClassTransform;

/**
 * Gradle plugin class for baseExtension library project shade dependencies.
 * <p/>
 * Created by ZhangZhenli on 2016/3/30.
 */
public class ShadePlugin implements Plugin<Project> {

    public static ShadePlugin instance;

    private final Instantiator instantiator;
    private final ToolingModelBuilderRegistry registry;
    protected Project project;
    public ShadeExtension extension;
    public BaseExtension baseExtension;
    private BasePlugin basePlugin;
    private ShadeTaskManager taskManager;
    private TaskFactory tasks;

    protected Logger logger;
    private ShadeAarClassTransform shadeAarClassTransform;

    @Inject
    public ShadePlugin(Instantiator instantiator, ToolingModelBuilderRegistry registry) {
        this.instantiator = instantiator;
        this.registry = registry;
        this.logger = Logging.getLogger(this.getClass());
        instance = this;
    }

    /**
     * 检查当前的Android Gradle Plugin 版本是否与当前的shade版本匹配.
     */
    private void verifyAndroidPlugin() {
        if (project.getPlugins().hasPlugin(AppPlugin.class)) {
            throw new BadPluginException(
                    "The 'com.baseExtension.application' plugin has been applied, but it is not compatible with the Android shade plugins.");
        }
        if (!project.getPlugins().hasPlugin(LibraryPlugin.class)) {
            throw new BadPluginException(
                    "The 'com.baseExtension.library' plugin not being applied, Android shade plugins does not work.");
        }
        String[] strings = Version.ANDROID_GRADLE_PLUGIN_VERSION.split("-");
        if (strings.length > 0 && strings[0].matches("^3.9.(\\*|\\d+)$")) {
            // version match
        } else {
            throw new ProjectConfigurationException("Android Shade Plugin needs and match the version " +
                    "used in conjunction with Android Plugin for Gradle. Requested 2.2.X,but found version " +
                    Version.ANDROID_GRADLE_PLUGIN_VERSION, null);
        }
    }

    @Override
    public void apply(Project target) {
        this.project = target;
        verifyAndroidPlugin();

        this.baseExtension = project.hasProperty("android") ? (BaseExtension) project.property("android") : null;
        this.basePlugin = project.getPlugins().getPlugin(LibraryPlugin.class);

        this.tasks = new TaskContainerAdaptor(project.getTasks());

        ThreadRecorder.get().record(ExecutionType.BASE_PLUGIN_PROJECT_BASE_EXTENSION_CREATION,
                project.getPath(), null /*variantName*/, new Recorder.Block<Void>() {
                    @Override
                    public Void call() throws Exception {
                        createExtension();
                        return null;
                    }
                });

        ThreadRecorder.get().record(ExecutionType.BASE_PLUGIN_PROJECT_TASKS_CREATION,
                project.getPath(), null /*variantName*/, new Recorder.Block<Void>() {
                    @Override
                    public Void call() throws Exception {
                        createTasks();
                        return null;
                    }
                });
    }

    /**
     * 配置插件扩展.
     */
    private void createExtension() {
        extension = project.getExtensions().create("androidShade", ShadeExtension.class,
                project, instantiator, baseExtension);
    }


    /**
     * 创建相关任务.
     */
    private void createTasks() throws IllegalAccessException {
        taskManager = TaskHelper.createShadeTaskManager(basePlugin);

        project.getTasks().create("shadeSimple", ShadeTask.class);

        shadeAarClassTransform = new ShadeAarClassTransform(project, (LibraryExtension) baseExtension, taskManager);
        baseExtension.registerTransform(shadeAarClassTransform);

        project.afterEvaluate(project -> {
            ThreadRecorder.get().record(ExecutionType.BASE_PLUGIN_CREATE_ANDROID_TASKS,
                    project.getPath(), null,
                    new Recorder.Block<Void>() {
                        @Override
                        public Void call() throws Exception {
                            createShadeActionTasks();
                            return null;
                        }
                    });
        });
    }

    /**
     * 合并操作.
     *
     * @throws IllegalAccessException
     */
    public void createShadeActionTasks() throws IllegalAccessException {

//        baseExtension.
//        for (VariantScope variantScope : basePlugin.getVariantManager().getVariantScopes()) {
//            ShadeJarToLocalTransform transform = new ShadeJarToLocalTransform(project, (LibraryExtension) ShadePlugin.instance.baseExtension, taskManager);
//            Optional<AndroidTask<TransformTask>> androidTask = variantScope.getTransformManager().addTransform(tasks, variantScope, transform);
//            androidTask.ifPresent(
//                    t -> {
////                        if (!deps.isEmpty()) {
////                            t.dependsOn(tasks, deps);
////                        }
//
//                        // if the task is a no-op then we make assemble task
//                        // depend on it.
//                        if (transform.getScopes().isEmpty()) {
//                            variantScope
//                                    .getAssembleTask()
//                                    .dependsOn(tasks, t);
//                        }
//                    });
//
//
//        }

        for (VariantScope variantScope : basePlugin.getVariantManager().getVariantScopes()) {
            BaseVariantData variantData = variantScope.getVariantData();
            if (variantData instanceof LibraryVariantData) {
                ThreadRecorder.get().record(
                        ExecutionType.VARIANT_MANAGER_CREATE_TASKS_FOR_VARIANT,
                        project.getPath(),
                        variantData.getName(),
                        new Recorder.Block<Void>() {
                            @Override
                            public Void call() throws Exception {
                                taskManager.createTasksForVariantScope(tasks, variantScope);
                                return null;
                            }
                        });
            }
        }
    }
}
