package com.house365.build

import android.databinding.tool.DataBindingBuilder
import com.android.build.gradle.AndroidConfig
import com.android.build.gradle.BasePlugin
import com.android.build.gradle.internal.LibraryTaskManager
import com.android.build.gradle.internal.SdkHandler
import com.android.build.gradle.internal.scope.GlobalScope
import com.android.builder.core.AndroidBuilder
import com.android.builder.profile.Recorder
import org.apache.commons.lang3.reflect.FieldUtils
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry


class TaskHelper {

    /**
     * 创建ShadeTaskManager对象.
     *
     * @param libraryTaskManager
     * @return
     */
    public static ShadeTaskManager createShadeTaskManager(BasePlugin basePlugin, LibraryTaskManager libraryTaskManager) {
        GlobalScope globalScope = libraryTaskManager.globalScope
        AndroidBuilder androidBuilder = libraryTaskManager.androidBuilder
        DataBindingBuilder dataBindingBuilder = libraryTaskManager.dataBindingBuilder
        AndroidConfig extension = libraryTaskManager.extension
        SdkHandler sdkHandler = libraryTaskManager.sdkHandler
        ToolingModelBuilderRegistry toolingRegistry = libraryTaskManager.toolingRegistry
        Recorder recorder = libraryTaskManager.recorder
        return new ShadeTaskManager(
                basePlugin,
                globalScope,
                libraryTaskManager.project,
                libraryTaskManager.projectOptions,
                androidBuilder,
                dataBindingBuilder,
                extension,
                sdkHandler,
                toolingRegistry,
                recorder);

    }

    public static ShadeTaskManager createShadeTaskManager(BasePlugin basePlugin) {
        createShadeTaskManager(basePlugin,FieldUtils.readField(basePlugin, "taskManager", true))
    }
}
