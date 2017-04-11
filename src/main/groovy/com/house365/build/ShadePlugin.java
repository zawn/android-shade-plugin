package com.house365.build;

import com.android.build.gradle.AppPlugin;
import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.BasePlugin;
import com.android.build.gradle.LibraryPlugin;
import com.android.build.gradle.internal.BadPluginException;
import com.android.build.gradle.internal.TaskContainerAdaptor;
import com.android.build.gradle.internal.TaskFactory;
import com.android.build.gradle.internal.TaskManager;
import com.android.build.gradle.internal.VariantManager;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.internal.variant.BaseVariantOutputData;
import com.android.build.gradle.internal.variant.LibraryVariantData;
import com.android.builder.model.Version;
import com.android.builder.profile.Recorder;
import com.android.builder.profile.ThreadRecorder;
import com.google.wireless.android.sdk.stats.GradleBuildProfileSpan.ExecutionType;
import com.house365.build.transform.ShadeJarToLocalTransform;
import com.house365.build.transform.ShadeAarClassTransform;
import com.house365.build.transform.ShadeJniLibsTransform;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.ProjectConfigurationException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import javax.inject.Inject;
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
    private ShadeJarToLocalTransform shadeJarToLocalTransform;
    private ShadeAarClassTransform shadeJarTransform;
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
        if (strings.length > 0 && strings[0].matches("^2.3.(\\*|\\d+)$")) {
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

        shadeTaskManager = new ShadeTaskManager(project, tasks, instantiator, basePlugin, baseExtension);

        shadeJarToLocalTransform = new ShadeJarToLocalTransform(project, baseExtension, shadeTaskManager);
        baseExtension.registerTransform(shadeJarToLocalTransform);
        shadeJarTransform = new ShadeAarClassTransform(project, baseExtension, shadeTaskManager);
        baseExtension.registerTransform(shadeJarTransform);

        shadeJniLibsTransform = new ShadeJniLibsTransform(project, baseExtension, shadeTaskManager);
        baseExtension.registerTransform(shadeJniLibsTransform);

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
        final VariantManager variantManager = (VariantManager) FieldUtils.readField(basePlugin, "variantManager", true);
        final TaskManager taskManager = (TaskManager) FieldUtils.readField(variantManager, "taskManager", true);
        List<BaseVariantData<? extends BaseVariantOutputData>> variantDataList = variantManager.getVariantDataList();
        for (BaseVariantData<? extends BaseVariantOutputData> baseVariantData : variantDataList) {
            if (baseVariantData instanceof LibraryVariantData) {
                final LibraryVariantData variantData = (LibraryVariantData) baseVariantData;

                ThreadRecorder.get().record(
                        ExecutionType.VARIANT_MANAGER_CREATE_TASKS_FOR_VARIANT,
                        project.getPath(),
                        variantData.getName(),
                        new Recorder.Block<Void>() {
                            @Override
                            public Void call() throws Exception {
                                shadeTaskManager.createTasksForVariantData(taskManager, variantData);
                                return null;
                            }
                        });
            }
        }
    }


}
