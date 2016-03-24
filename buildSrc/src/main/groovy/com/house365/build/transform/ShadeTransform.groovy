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
import com.android.build.gradle.AndroidGradleOptions
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.internal.api.LibraryVariantImpl
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.scope.ConventionMappingHelper
import com.android.build.gradle.internal.scope.GlobalScope
import com.android.build.gradle.internal.transforms.JarMerger
import com.android.build.gradle.internal.variant.LibraryVariantData
import com.android.builder.core.VariantConfiguration
import com.android.builder.dependency.JarDependency
import com.android.builder.dependency.LibraryDependency
import com.android.builder.signing.SignedJarBuilder
import com.android.ide.common.res2.AssetSet
import com.android.ide.common.res2.ResourceSet
import com.android.utils.FileUtils
import com.google.common.collect.Lists
import com.google.common.collect.Sets
import com.house365.build.AndroidShadePlugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.internal.ConventionMapping

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
    private static final boolean DEBUG = true;

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
        LinkedHashSet<File> needCombineSet = getNeedCombineJars(project, variant.getVariantData())
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
                println "combine jar: " + file
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
     * 获取需要合并的文件包含JAR/AAR.
     * @param project
     * @param variant
     * @return
     */
    public static LinkedHashSet<File> getNeedCombineFiles(Project project,
                                                          LibraryVariantData variantData) {
        Set<File> needCombineSet = new LinkedHashSet<>()
        for (AndroidSourceSet sourceSet : variantData.variantConfiguration.getSortedSourceProviders()) {
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
            println "------------------"
            println shadeConfiguration.allDependencies
            return shadeConfiguration.files
        }
        return null
    }

    /**
     * 获取要合并的AAR.
     *
     * @param variantData
     * @param combinedSet
     */
    public
    static List<LibraryDependency> getNeedCombineAar(LibraryVariantData variantData, Set<File> combinedSet) {
        List<LibraryDependency> combinedLibraries = new ArrayList<>()
        List<LibraryDependency> mFlatLibraries = variantData.variantConfiguration.getAllLibraries();
        for (int n = mFlatLibraries.size() - 1; n >= 0; n--) {
            LibraryDependency dependency = mFlatLibraries.get(n);
            if (combinedSet.contains(dependency.getBundle())) {
                combinedLibraries.add(dependency)
            }
        }
        return combinedLibraries
    }

    public
    static LinkedHashSet<File> getNeedCombineJars(Project project, LibraryVariantData variantData) {
        Set<File> needCombineJars = new LinkedHashSet<>()
        Set<File> needCombineSet = getNeedCombineFiles(project, variantData)
        for (File file : needCombineSet) {
            if (file.getName().toLowerCase().endsWith(".jar")) {
                needCombineJars.add(file)
            }
        }
        List<LibraryDependency> needCombineAar = getNeedCombineAar(variantData, needCombineSet)
        for (LibraryDependency dependency : needCombineAar) {
            File jarFile = dependency.getJarFile();
            if (jarFile.exists()) {
                needCombineJars.add(jarFile)
            }
        }
        return needCombineJars
    }

    /**
     * 将Shade AAR中的Resource合并进bundle.
     *
     * @param variantData
     * @param combinedSet
     */
    public
    static void addResourceToBundle(LibraryVariantData variantData, List<LibraryDependency> libraryDependencies) {
        final boolean validateEnabled = AndroidGradleOptions.isResourceValidationEnabled(
                variantData.getScope().getGlobalScope().getProject());
        List<ResourceSet> resourceSets = Lists.newArrayList();
        for (int n = libraryDependencies.size() - 1; n >= 0; n--) {
            LibraryDependency dependency = libraryDependencies.get(n);
            println "\n\n ResFolder: " + dependency.getResFolder()
            File resFolder = dependency.getResFolder();
            if (resFolder.isDirectory()) {
                ResourceSet resourceSet =
                        new ResourceSet(dependency.getFolder().getName(), validateEnabled);
                resourceSet.addSource(resFolder);
                resourceSet.setFromDependency(true);
                resourceSets.add(resourceSet);
            }
        }

        ConventionMapping conventionMapping =
                (ConventionMapping) ((GroovyObject) variantData.mergeResourcesTask).getProperty("conventionMapping");
        resourceSets.addAll(conventionMapping.getConventionValue(new ArrayList<ResourceSet>(), "inputResourceSets", false));
        ConventionMappingHelper.map(variantData.mergeResourcesTask, "inputResourceSets") {
            resourceSets
        }

        if (DEBUG) {
            println "Combined with all the resource"
            resourceSets.each { ResourceSet assetSet ->
                println assetSet
            }
            println ""
        }
    }

    /**
     * 将Shade AAR中的Asset合并进bundle.
     *
     * @param variantData
     * @param combinedSet
     */
    public
    static void addAssetsToBundle(LibraryVariantData variantData, List<LibraryDependency> libraryDependencies) {
        List<AssetSet> assetSets = Lists.newArrayList();
        for (int n = libraryDependencies.size() - 1; n >= 0; n--) {
            LibraryDependency dependency = libraryDependencies.get(n);
            File assetFolder = dependency.getAssetsFolder();
            if (assetFolder.isDirectory()) {
                AssetSet assetSet = new AssetSet(dependency.getFolder().getName());
                assetSet.addSource(assetFolder);
                assetSets.add(assetSet);
            }
        }

        ConventionMapping conventionMapping =
                (ConventionMapping) ((GroovyObject) variantData.mergeAssetsTask).getProperty("conventionMapping");
        assetSets.addAll(conventionMapping.getConventionValue(new ArrayList<AssetSet>(), "inputDirectorySets", false));
        ConventionMappingHelper.map(variantData.mergeAssetsTask, "inputDirectorySets") {
            assetSets
        }

        if (DEBUG) {
            println "Combined with all the asset"
            assetSets.each { AssetSet assetSet ->
                println assetSet
            }
            println ""
        }
    }

    /**
     * 从依赖中删除已经合并的jar
     *
     */
    public static void removeCombinedJar(VariantConfiguration variantConfiguration,
                                         Set<File> combinedSet) {
        Field declaredField
        try {
            //2.0.0-beta6 or later
            declaredField = VariantConfiguration.class.getDeclaredField("mJarDependencies");
        } catch (NoSuchFieldException e) {
            declaredField = VariantConfiguration.class.getDeclaredField("mExternalJars");
        }
        declaredField.setAccessible(true)
        Collection<JarDependency> externalJarDependencies = declaredField.get(variantConfiguration)
        HashSet<JarDependency> set = new HashSet<>();
        for (JarDependency jar : externalJarDependencies) {
            File jarFile = jar.getJarFile();
            if (!combinedSet.contains(jarFile)) {
                set.add(jar)
            } else {
                println("Remove combine jar :" + jarFile)
            }
        }
        declaredField.set(variantConfiguration, set)
    }
}

