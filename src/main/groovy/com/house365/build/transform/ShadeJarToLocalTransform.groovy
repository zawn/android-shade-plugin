package com.house365.build.transform

import com.android.annotations.NonNull
import com.android.annotations.Nullable
import com.android.build.api.transform.*
import com.android.build.api.transform.QualifiedContent.ContentType
import com.android.build.api.transform.QualifiedContent.Scope
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.api.UnitTestVariant
import com.android.build.gradle.internal.api.LibraryVariantImpl
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.variant.LibraryVariantData
import com.android.utils.FileUtils
import com.google.common.collect.Sets
import com.house365.build.ShadeTaskManager
import org.apache.commons.io.FilenameUtils
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import static com.google.common.base.Preconditions.checkNotNull

/**
 * 将需要Shade的所有Jar文件放至本地.
 */
class ShadeJarToLocalTransform extends Transform {
    public static final boolean DEBUG = true;
    private final Logger logger;
    private BaseVariant variant
    private LibraryExtension libraryExtension
    private boolean isLibrary = true;
    private Project project
    private VariantScope variantScope
    private ShadeTaskManager shadeTaskManager


    ShadeJarToLocalTransform(Project project, BaseExtension LibraryExtension, ShadeTaskManager shadeTaskManager) {
        this.shadeTaskManager = shadeTaskManager
        this.project = project
        this.libraryExtension = LibraryExtension
        this.logger = Logging.getLogger(ShadeJarToLocalTransform.class);
    }

    @NonNull
    @Override
    String getName() {
        return "shadeJarToLocal";
    }

    @NonNull
    @Override
    Set<ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }

    @Override
    boolean isIncremental() {
        return false;
    }

    @NonNull
    @Override
    Set<Scope> getScopes() {
        return Sets.immutableEnumSet(Scope.PROJECT_LOCAL_DEPS);
    }

    @Override
    void transform(TransformInvocation invocation) throws TransformException, InterruptedException, IOException {
        this.variant = ShadeAarClassTransform.getCurrentVariant(libraryExtension, invocation)
        if (variant instanceof TestVariant || variant instanceof UnitTestVariant) {
            // 不应该处测试相关Variant.
            return
        }
        @Nullable TransformOutputProvider outputProvider = invocation.getOutputProvider()
        checkNotNull(outputProvider, "Missing output object for transform " + getName())
        if (DEBUG)
            for (TransformInput input : invocation.getInputs()) {
                for (JarInput jarInput : input.getJarInputs()) {
                    println jarInput.getFile()
                }

                for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                    println directoryInput.getFile();
                }
            }
        LibraryVariantData variantData
        if (variant instanceof LibraryVariantImpl) {
            variantData = variant.getVariantData()
            variantScope = variantData.getScope()
            isLibrary = this.variantScope.getVariantData() instanceof LibraryVariantData;
            if (!isLibrary)
                throw new ProjectConfigurationException("The shade plugin only be used for android library.", null)
        }
        shadeTaskManager.getVariantShadeJars(variantData.getName()).each {
            File distJarFile = outputProvider.getContentLocation(FilenameUtils.getBaseName(it.getClasspathFile().getName()), getOutputTypes(), getScopes(),
                    Format.JAR);
            if (!distJarFile.getParentFile().exists()) {
                distJarFile.getParentFile().mkdirs()
            }
            FileUtils.copyFile(it.getClasspathFile(), distJarFile)
        };
    }
}

