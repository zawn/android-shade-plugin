package com.house365.build.task

import com.android.builder.core.VariantConfiguration
import com.house365.build.transform.ShadeTransform
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Created by ZhangZhenli on 2016/1/27.
 */
class ClassPathTask extends DefaultTask {

    VariantConfiguration variantConfiguration;

    @TaskAction
    def taskAction() {
        LinkedHashSet<File> files = ShadeTransform.getNeedCombineFiles(getProject(), variantConfiguration);
        ShadeTransform.removeCombinedJar(variantConfiguration, files)
    }
}
