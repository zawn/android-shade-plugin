package com.house365.build.task

import com.android.build.gradle.internal.variant.LibraryVariantData
import com.house365.build.transform.ShadeTransform
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Created by ZhangZhenli on 2016/1/27.
 */
class ClassPathTask extends DefaultTask {

    LibraryVariantData variantData;

    @TaskAction
    def taskAction() {
        LinkedHashSet<File> files = ShadeTransform.getNeedCombineFiles(getProject(), variantData);
        ShadeTransform.removeCombinedJar(variantData.getVariantConfiguration(), files)
    }
}
