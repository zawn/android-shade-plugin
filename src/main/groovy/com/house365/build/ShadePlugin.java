package com.house365.build;

import com.android.build.api.transform.Transform;
import com.android.build.gradle.AppPlugin;
import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.BasePlugin;
import com.android.build.gradle.LibraryPlugin;
import com.android.build.gradle.internal.BadPluginException;
import com.android.build.gradle.internal.TaskContainerAdaptor;
import com.android.build.gradle.internal.TaskFactory;
import com.android.build.gradle.internal.TaskManager;
import com.android.build.gradle.internal.VariantManager;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.profile.SpanRecorders;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.internal.variant.BaseVariantOutputData;
import com.android.build.gradle.internal.variant.LibraryVariantData;
import com.android.builder.model.Version;
import com.android.builder.profile.Recorder;
import com.android.builder.profile.ThreadRecorder;
import com.google.wireless.android.sdk.stats.AndroidStudioStats.GradleBuildProfileSpan.ExecutionType;
import com.house365.build.task.ClassPathTask;
import com.house365.build.transform.ShadeJarTransform;
import com.house365.build.transform.ShadeJniLibsTransform;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.ProjectConfigurationException;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import javax.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Gradle plugin class for baseExtension library project shade dependencies.
 * <p/>
 * Created by ZhangZhenli on 2016/3/30.
 */
public class ShadePlugin implements Plugin<Project> {

    private final Instantiator instantiator;
    private final ToolingModelBuilderRegistry registry;
    protected Project project;
    private ShadeExtension extension;
    private BaseExtension baseExtension;
    private BasePlugin basePlugin;
    private ShadeTaskManager shadeTaskManager;
    private TaskFactory tasks;

    protected Logger logger;
    private ShadeJarTransform shadeJarTransform;
    private ShadeJniLibsTransform shadeJniLibsTransform;

    @Inject
    public ShadePlugin(Instantiator instantiator, ToolingModelBuilderRegistry registry) {
        this.instantiator = instantiator;
        this.registry = registry;
        this.logger = Logging.getLogger(this.getClass());
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
        if (strings.length > 0 && strings[0].matches("^2.2.(\\*|\\d+)$")) {
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
        System.out.println("ShadePlugin.createTasks");
        shadeJarTransform = new ShadeJarTransform(project, baseExtension);
        baseExtension.registerTransform(shadeJarTransform);

        shadeJniLibsTransform = new ShadeJniLibsTransform(project, baseExtension);
        baseExtension.registerTransform(shadeJniLibsTransform);

        shadeTaskManager = new ShadeTaskManager(project, tasks, instantiator, basePlugin, baseExtension);
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
        System.out.println("ShadePlugin.createShadeActionTasks");
        final VariantManager variantManager = (VariantManager) FieldUtils.readField(basePlugin, "variantManager", true);
        final TaskManager taskManager = (TaskManager) FieldUtils.readField(variantManager, "taskManager", true);
        List<BaseVariantData<? extends BaseVariantOutputData>> variantDataList = variantManager.getVariantDataList();
        for (BaseVariantData<? extends BaseVariantOutputData> baseVariantData : variantDataList) {
            if (baseVariantData instanceof LibraryVariantData) {
                final LibraryVariantData variantData = (LibraryVariantData) baseVariantData;

                SpanRecorders.record(
                        project,
                        variantData.getName(),
                        ExecutionType.VARIANT_MANAGER_CREATE_TASKS_FOR_VARIANT,
                        new Recorder.Block<Void>() {
                            @Override
                            public Void call() throws Exception {
//                                createTasksForVariantData(tasks, variantData);
                                shadeTaskManager.createTasksForVariantData(taskManager, variantData);
                                return null;
                            }
                        });
            }
        }
    }

    public void cleanClassPath(LibraryVariantData variantData) {
        String prefix = null;
        try {
            Method method = TransformManager.class.getDeclaredMethod("getTaskNamePrefix", Transform.class);
            method.setAccessible(true);
            prefix = (String) method.invoke(null, shadeJarTransform);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
        String taskName = variantData.getScope().getTaskName(prefix);
        // 大写第一个字母
        String pinyin = String.valueOf(taskName.charAt(0)).toUpperCase().concat(taskName.substring(1));
        ClassPathTask classPathCleanTask = project.getTasks().create("clean" + pinyin, ClassPathTask.class);
        classPathCleanTask.setVariantData(variantData);
        Task task = project.getTasks().findByName(taskName);
        task.finalizedBy(classPathCleanTask);

        //transformClassesWithShadeJarForRelease
        //transformResourcesWithMergeJavaResForRelease
        project.getTasks().findByName(taskName.replace("ShadeJar", "MergeJavaRes").replace("Classes", "Resources")).dependsOn(task);

    }


}
