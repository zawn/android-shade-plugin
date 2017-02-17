/*
 * Copyright (C) 2015 House365. All rights reserved.
 */

package com.house365.build.transform

import com.android.annotations.NonNull
import com.android.annotations.Nullable
import com.android.build.api.transform.*
import com.android.build.api.transform.QualifiedContent.ContentType
import com.android.build.api.transform.QualifiedContent.Scope
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.internal.api.LibraryVariantImpl
import com.android.build.gradle.internal.packaging.ParsedPackagingOptions
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.scope.GlobalScope
import com.android.build.gradle.internal.transforms.JarMerger
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.build.gradle.internal.variant.LibraryVariantData
import com.android.builder.core.DefaultManifestParser
import com.android.builder.dependency.LibraryDependency
import com.android.builder.dependency.MavenCoordinatesImpl
import com.android.builder.model.AndroidLibrary
import com.android.builder.model.MavenCoordinates
import com.android.utils.FileUtils
import com.google.common.collect.Lists
import com.google.common.collect.Sets
import com.house365.build.util.ZipEntryFilterUtil
import com.tonicsystems.jarjar.PatternElement
import com.tonicsystems.jarjar.RulesFileParser
import com.tonicsystems.jarjar.util.StandaloneJarProcessor
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import static com.android.utils.FileUtils.deleteIfExists
import static com.google.common.base.Preconditions.checkNotNull

/**
 * A transform that merges all the incoming inputs (folders and jars) into a single jar in a
 * single combined output.
 * <p/>
 * This only packages the class files. It ignores other files.
 */
public class ShadeJarTransform extends Transform {
    public static final boolean DEBUG = false;
    private final Logger logger;
    private BaseVariant variant
    private LibraryExtension libraryExtension
    private boolean isLibrary = true;
    private Project project
    private variantScope


    public ShadeJarTransform(Project project, BaseExtension LibraryExtension) {
        this.project = project
        this.libraryExtension = LibraryExtension
        this.logger = Logging.getLogger(ShadeJarTransform.class);
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
            return Sets.immutableEnumSet(Scope.PROJECT);
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
        File jarFile = outputProvider.getContentLocation("combined-temp", getOutputTypes(), getScopes(),
                Format.JAR);
        File rJarFile = outputProvider.getContentLocation("r", getOutputTypes(), getScopes(),
                Format.JAR);
        FileUtils.mkdirs(jarFile.getParentFile());
        deleteIfExists(jarFile);

        if (DEBUG)
            for (TransformInput input : inputs) {
                for (JarInput jarInput : input.getJarInputs()) {
                    println jarInput.getFile()
                }

                for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                    println directoryInput.getFile();
                }
            }

        this.variant = getCurrentVariantScope(libraryExtension, this, jarFile)


        def variantData = null
        if (variant instanceof LibraryVariant) {
            variantData = variant.getVariantData()
            variantScope = variantData.getScope()
            isLibrary = this.variantScope.getVariantData() instanceof LibraryVariantData;
            if (!isLibrary)
                throw new ProjectConfigurationException("The shade plugin only be used for android library.", null)
        }
        List<LibraryDependency> libraryDependencies = findShadeLibraries(project, variantData);
        LinkedHashSet<File> needCombineJars = new LinkedHashSet<>();
        libraryDependencies.each {
            needCombineJars.add(it.jarFile)
            it.skip()
            if (DEBUG)
                println("Remove combine jar :" + it.getJarFile())
        }


        def packagingOptions = variantScope.getGlobalScope().getExtension().getPackagingOptions()
        def parsedPackagingOptions = new ParsedPackagingOptions(packagingOptions)
        jarMerger(jarFile, inputs, needCombineJars,
                new ZipEntryFilterUtil.JarWhitoutRFilter(parsedPackagingOptions, null))
        jarMerger(rJarFile, inputs, null,
                new ZipEntryFilterUtil.JarRFilter(parsedPackagingOptions, null))

        if (variant instanceof LibraryVariant) {
            File outJar = outputProvider.getContentLocation("combined", getOutputTypes(), getScopes(),
                    Format.JAR);
            // JarJar
            jarjar(variantData, jarFile, outJar)
            jarFile.delete()
        }
    }

    private void jarMerger(File jarFile, Collection<TransformInput> inputs, LinkedHashSet<File> needCombineJars, ZipEntryFilterUtil.PackagingFilter filter) {
        JarMerger jarMerger = new JarMerger(jarFile);
        try {
            jarMerger.setFilter(filter);
            for (TransformInput input : inputs) {
                for (JarInput jarInput : input.getJarInputs()) {
                    filter.reset(jarInput.getFile())
                    jarMerger.addJar(jarInput.getFile());
                }

                for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                    filter.reset(directoryInput.getFile())
                    jarMerger.addFolder(directoryInput.getFile());
                }
            }
            if (needCombineJars != null && needCombineJars.size() > 0)
                for (File file : needCombineJars) {
                    println "combine jar: " + file
                    filter.reset(file)
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

    def jarjar(BaseVariantData baseVariantData, File jarFile, File outJar) {
        StringBuilder stringBuilder = new StringBuilder();
        String appPackageName = new DefaultManifestParser(baseVariantData.variantConfiguration.getMainManifest()).getPackage();
        Set<AndroidLibrary> libraries = findShadeLibraries(project, baseVariantData)
        if (libraries.size() > 0)
            println "Project PackageName:" + appPackageName
        libraries.each {
            def manifestPackage = new DefaultManifestParser(it.getManifest()).getPackage();
            println "Library PackageName:" + manifestPackage

            def rule = "rule " + manifestPackage + ".R*  " + appPackageName + ".R@1"
            println "   " + rule
            stringBuilder.append(rule).append("\r\n");
        }
        List<PatternElement> rules = RulesFileParser.parse(stringBuilder.toString());
        boolean verbose = logger.isEnabled(LogLevel.INFO);
        boolean skipManifest = false
        com.android.utils.FileUtils.mkdirs(outJar.getParentFile());
        deleteIfExists(outJar);
        outJar.createNewFile();
        def constructor = Class.forName("com.tonicsystems.jarjar.MainProcessor").getDeclaredConstructors()[0]
        constructor.setAccessible(true)
        def mainProcessor = constructor.newInstance(rules, verbose, skipManifest)
        StandaloneJarProcessor.run(jarFile, outJar, mainProcessor)
    }

    static BaseVariant getCurrentVariantScope(LibraryExtension libraryExtension, Transform transform, File file) {
        for (LibraryVariantImpl variant : libraryExtension.libraryVariants) {
            LibraryVariantData variantData = variant.variantData
            GlobalScope globalScope = variantData.getScope().getGlobalScope();
            File parentFile = new File(globalScope.getIntermediatesDir(), "/transforms/" + transform.getName() + "/" +
                    variant.getDirName())
            if (checkIsParent(file, parentFile))
                return variant;
            parentFile = new File(globalScope.getIntermediatesDir(), "/transforms/" + transform.getName() + "/" +
                    variant.testVariant.getDirName())
            if (checkIsParent(file, parentFile))
                return variant.testVariant;
            parentFile = new File(globalScope.getIntermediatesDir(), "/transforms/" + transform.getName() + "/" +
                    variant.unitTestVariant.getDirName())
            if (checkIsParent(file, parentFile))
                return variant.unitTestVariant;
        }
        return null
    }

    static boolean checkIsParent(File child, File possibleParent) {
        if (DEBUG)
            println "ShadeJarTransform.checkIsParent\n" + child.toString() + "\n" + possibleParent.toString()
        return child.getAbsolutePath().startsWith(possibleParent.getAbsolutePath());
    }


    static List<LibraryDependency> findShadeLibraries(
            Project project,
            LibraryVariantData libraryVariantData) {
        Set<MavenCoordinates> artifacts = ShadeJarToLocalTransform.findVariantShadeDependenciesMavenCoordinates(project, libraryVariantData);
        List<AndroidLibrary> androidLibraries = libraryVariantData.variantConfiguration.getCompileAndroidLibraries();
        LinkedList<LibraryDependency> combinedLibraries = new LinkedList();
        androidLibraries.each { AndroidLibrary library ->
            def all = artifacts.findAll { MavenCoordinatesImpl coordinates ->
                coordinates.compareWithoutVersion(library.getResolvedCoordinates())
            }
            if (all.size() > 0) {
                if (library instanceof LibraryDependency2) {
                    combinedLibraries.add(library.getOriginal())
                } else {
                    combinedLibraries.add(library)
                }
            }
        }
        return combinedLibraries;
    }

    public static class LibraryDependency2 extends LibraryDependency {

        private LibraryDependency dependency

        LibraryDependency2(LibraryDependency dependencyImpl) {
            super(
                    dependencyImpl.getBundle(),
                    dependencyImpl.getFolder(),
                    dependencyImpl.dependencies,
                    dependencyImpl.name,
                    dependencyImpl.variantName,
                    dependencyImpl.getProject(),
                    dependencyImpl.requestedCoordinates,
                    dependencyImpl.resolvedCoordinates,
                    dependencyImpl.isOptional
            )
            this.dependency = dependencyImpl
        }

        LibraryDependency getOriginal() {
            return dependency;
        }

        @Override
        File getJarFile() {
            return new File(this.getJarsRootFolder(), "none");
        }

        @Override
        List<File> getLocalJars() {
            return Lists.newArrayList();
        }

        @Override
        boolean isOptional() {
            return false
        }

        @Override
        public String toString() {
            return super.toString()
        }
    }
}

