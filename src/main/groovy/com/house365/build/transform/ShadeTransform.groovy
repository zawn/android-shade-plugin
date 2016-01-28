/*
 * Copyright (C) 2015 House365. All rights reserved.
 */

package com.house365.build.transform

import com.android.SdkConstants
import com.android.annotations.NonNull
import com.android.annotations.Nullable
import com.android.build.api.transform.*
import com.android.build.api.transform.QualifiedContent.ContentType
import com.android.build.api.transform.QualifiedContent.Scope
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.internal.api.LibraryVariantImpl
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.scope.GlobalScope
import com.android.build.gradle.internal.transforms.JarMerger
import com.android.build.gradle.internal.variant.LibraryVariantData
import com.android.builder.core.VariantConfiguration
import com.android.builder.dependency.JarDependency
import com.android.builder.signing.SignedJarBuilder
import com.android.utils.FileUtils
import com.google.common.collect.Sets
import com.house365.build.AndroidShadePlugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.ConfigurationContainer

import java.lang.reflect.Field

import static com.android.utils.FileUtils.deleteIfExists
import static com.google.common.base.Preconditions.checkNotNull

/**
 * A transform that merges all the incoming inputs (folders and jars) into a single jar in a
 * single combined output.
 * <p/>
 * This only packages the class files. It ignores other files.
 */
public class ShadeTransform extends Transform {

    private LibraryVariantImpl variant
    private LibraryExtension libraryExtension
    private boolean isLibrary = true;
    private Project project
    private variantScope
    private static logger = org.slf4j.LoggerFactory.getLogger(ShadeTransform.class)


    public ShadeTransform(Project project, LibraryExtension LibraryExtension) {
        this.project = project
        this.libraryExtension = LibraryExtension
    }

    @NonNull
    @Override
    public String getName() {
        return "shadeJar";
    }

    @NonNull
    @Override
    public Set<ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }

    @Override
    public boolean isIncremental() {
        return false;
    }

    @NonNull
    @Override
    public Set<Scope> getScopes() {
        if (isLibrary) {
            return Sets.immutableEnumSet(Scope.PROJECT, Scope.PROJECT_LOCAL_DEPS);
        }
        return TransformManager.SCOPE_FULL_PROJECT;
    }


    @Override
    public void transform(
            @NonNull Context context,
            @NonNull Collection<TransformInput> inputs,
            @NonNull Collection<TransformInput> referencedStreams,
            @Nullable TransformOutputProvider outputProvider,
            boolean isIncremental) throws TransformException, IOException {
        checkNotNull(outputProvider, "Missing output object for transform " + getName());

        // all the output will be the same since the transform type is COMBINED.
        // and format is SINGLE_JAR so output is a jar
        File jarFile = outputProvider.getContentLocation("combined", getOutputTypes(), getScopes(),
                Format.JAR);
        this.variant = getCurrentVariantScope(jarFile)
        variantScope = variant.variantData.getScope()
        isLibrary = this.variantScope.getVariantData() instanceof LibraryVariantData;
        if (!isLibrary)
            throw new ProjectConfigurationException("The shade plugin only be used for android library.", null)

        LinkedHashSet<File> needCombineSet = getNeedCombineFiles(project, variant.getVariantData().getVariantConfiguration())
        FileUtils.mkdirs(jarFile.getParentFile());
        deleteIfExists(jarFile);

        JarMerger jarMerger = new JarMerger(jarFile);
        try {
            jarMerger.setFilter(new SignedJarBuilder.IZipEntryFilter() {
                @Override
                public boolean checkEntry(String archivePath)
                        throws com.android.sdklib.internal.build.SignedJarBuilder.IZipEntryFilter.ZipAbortException {
                    return archivePath.endsWith(SdkConstants.DOT_CLASS);
                }
            });
            for (TransformInput input : inputs) {
                for (JarInput jarInput : input.getJarInputs()) {
                    jarMerger.addJar(jarInput.getFile());
                }

                for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                    jarMerger.addFolder(directoryInput.getFile());
                }
            }
            for (File file : needCombineSet) {
                jarMerger.addJar(file)
            }
        } catch (FileNotFoundException e) {
            throw new TransformException(e);
        } catch (IOException e) {
            throw new TransformException(e);
        } finally {
            jarMerger.close();
        }
    }


    LibraryVariantImpl getCurrentVariantScope(File file) {
        for (LibraryVariantImpl variant : libraryExtension.libraryVariants) {
            LibraryVariantData variantData = variant.variantData
            GlobalScope globalScope = variantData.getScope().getGlobalScope();
            File parentFile = new File(globalScope.getIntermediatesDir(), "/transforms/" + this.getName() + "/" +
                    variantData.getVariantConfiguration().getDirName())
            if (checkIsParent(file, parentFile))
                return variant;
        }
        return null
    }

    boolean checkIsParent(File child, File possibleParent) {
        return child.getAbsolutePath().startsWith(possibleParent.getAbsolutePath());
    }

    /**
     * 获取需要合并的jar.
     * @param project
     * @param variant
     * @return
     */
    public static LinkedHashSet<File> getNeedCombineFiles(Project project,
                                                          VariantConfiguration variantConfiguration) {
        Set<File> needCombineSet = new LinkedHashSet<>()
        for (AndroidSourceSet sourceSet : variantConfiguration.getSortedSourceProviders()) {
            Set<File> files = getShadeLibs(project.configurations, sourceSet)
            if (files != null)
                needCombineSet.addAll(files)
        }
        return needCombineSet
    }

    private static Set<File> getShadeLibs(@NonNull ConfigurationContainer configurations,
                                          @NonNull AndroidSourceSet sourceSet) {
        this.logger.info("sourceSet Name :" + sourceSet.getName())
        def shadeConfigurationName = AndroidShadePlugin.getShadeConfigurationName(sourceSet.getName())
        def shadeConfiguration = configurations.findByName(shadeConfigurationName);
        if (shadeConfiguration != null) {
            this.logger.info("Find configuration " + shadeConfigurationName)
            return shadeConfiguration.files
        }
        return null
    }

    /**
     * 从以来中删除已经合并的jar
     *
     */
    public static void removeCombinedJar(VariantConfiguration variantConfiguration,
                                         Set<File> combinedSet) {
        Collection<JarDependency> externalJarDependencies = variantConfiguration.getExternalJarDependencies()
        HashSet<JarDependency> set = new HashSet<>();
        for (JarDependency jar : externalJarDependencies) {
            File jarFile = jar.getJarFile();
            if (!combinedSet.contains(jarFile)) {
                set.add(jar)
            } else {
                println("Remove combine jar :" + jarFile)
            }
        }
        Field declaredField = VariantConfiguration.class.getDeclaredField("mExternalJars");
        declaredField.setAccessible(true)
        declaredField.set(variantConfiguration, set)
    }
}

