package com.house365.build.transform

import com.android.annotations.NonNull
import com.android.annotations.Nullable
import com.android.build.api.transform.*
import com.android.build.api.transform.QualifiedContent.ContentType
import com.android.build.api.transform.QualifiedContent.Scope
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.api.LibraryVariantImpl
import com.android.build.gradle.internal.core.GradleVariantConfiguration
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.variant.LibraryVariantData
import com.android.builder.dependency.DependencyContainer
import com.android.builder.dependency.DependencyContainerImpl
import com.android.builder.dependency.JarDependency
import com.android.builder.dependency.MavenCoordinatesImpl
import com.android.builder.model.AndroidLibrary
import com.android.builder.model.JavaLibrary
import com.android.builder.model.MavenCoordinates
import com.android.utils.FileUtils
import com.google.common.collect.ImmutableList
import com.google.common.collect.Sets
import com.house365.build.ShadeExtension
import org.apache.commons.io.FilenameUtils
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.*
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
    private variantScope


    ShadeJarToLocalTransform(Project project, BaseExtension LibraryExtension) {
        this.project = project
        this.libraryExtension = LibraryExtension
        this.logger = Logging.getLogger(ShadeJarToLocalTransform.class);
    }

    @NonNull
    @Override
    public String getName() {
        return "shadeJarToLocal";
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
        return Sets.immutableEnumSet(Scope.PROJECT_LOCAL_DEPS);
    }


    @Override
    public void transform(
            @NonNull Context context,
            @NonNull Collection<TransformInput> inputs,
            @NonNull Collection<TransformInput> referencedStreams,
            @Nullable TransformOutputProvider outputProvider,
            boolean isIncremental) throws TransformException, IOException {
        checkNotNull(outputProvider, "Missing output object for transform " + getName());

        if (DEBUG)
            for (TransformInput input : inputs) {
                for (JarInput jarInput : input.getJarInputs()) {
                    println jarInput.getFile()
                }

                for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                    println directoryInput.getFile();
                }
            }
        // 用于确定当前variant,无实际使用.
        File jarFile = outputProvider.getContentLocation("combined-temp", getOutputTypes(), getScopes(),
                Format.JAR);
        FileUtils.mkdirs(jarFile.getParentFile());
        this.variant = ShadeJarTransform.getCurrentVariantScope(libraryExtension, this, jarFile)

        LibraryVariantData variantData
        if (variant instanceof LibraryVariantImpl) {
            variantData = variant.getVariantData()
            variantScope = variantData.getScope()
            isLibrary = this.variantScope.getVariantData() instanceof LibraryVariantData;
            if (!isLibrary)
                throw new ProjectConfigurationException("The shade plugin only be used for android library.", null)
        }

        DependencyContainer originalCompileDependencies = variantData.variantDependency.compileDependencies
        List<? extends AndroidLibrary> newLibraryDependencies = new LinkedList<>(originalCompileDependencies.androidDependencies)
        Collection<? extends JavaLibrary> newJavaDependencies = new LinkedList<>(originalCompileDependencies.jarDependencies)
        Collection<? extends JavaLibrary> newLocalJars = new LinkedList<>(originalCompileDependencies.localDependencies)

        Set<MavenCoordinates> mavenCoordinates = findVariantShadeDependenciesMavenCoordinates(project, variantData)
        flatNeedShadeJavaDependencies(newJavaDependencies, mavenCoordinates, newLocalJars)
        flatNeedShadeLibraryDependencies(newLibraryDependencies, mavenCoordinates, newLocalJars)

        def compileDependencies = new DependencyContainerImpl(newLibraryDependencies, newJavaDependencies, newLocalJars);
        newLocalJars.each {
            File distJarFile = outputProvider.getContentLocation(FilenameUtils.getBaseName(it.getJarFile().getName()), getOutputTypes(), getScopes(),
                    Format.JAR);
            FileUtils.copyFile(it.getJarFile(), distJarFile)
        }
        variantData.variantDependency.setDependencies(compileDependencies, variantData.variantDependency.packageDependencies)
    }

    /**
     * 将newJavaDependencies中需要Shade的Jar依赖(包含其本地依赖以及Maven依赖以及关联依赖)提取出来并放至newLocalJars.
     *
     * @param newJavaDependencies
     * @param mavenCoordinates
     * @param newLocalJars
     */
    private void flatNeedShadeJavaDependencies(
            LinkedList<? extends JavaLibrary> newJavaDependencies,
            LinkedHashSet<MavenCoordinates> mavenCoordinates,
            LinkedList<? extends JavaLibrary> newLocalJars) {
        for (int i = 0; i < newJavaDependencies.size(); i++) {
            JavaLibrary javaLibrary = newJavaDependencies.get(i)
            def coordinates = mavenCoordinates.findAll { MavenCoordinatesImpl it ->
                it.compareWithoutVersion(javaLibrary.getResolvedCoordinates())
            }
            if (coordinates.size() > 0) {
                i--;
                newJavaDependencies.remove(javaLibrary);
                flatJavaLibrary(javaLibrary, newLocalJars);
            }
        }
    }

    /**
     * 将newLibraryDependencies中需要Shade的AAR的Jar依赖(包含其本地依赖以及Maven依赖以及关联依赖)提取出来并放至newLocalJars.
     *
     * @param newLibraryDependencies
     * @param mavenCoordinates
     * @param newLocalJars
     */
    private void flatNeedShadeLibraryDependencies(
            List<? extends AndroidLibrary> newLibraryDependencies,
            Set<MavenCoordinates> mavenCoordinates,
            Collection<? extends JavaLibrary> newLocalJars) {
        for (int i = 0; i < newLibraryDependencies.size(); i++) {
            AndroidLibrary androidLibrary = newLibraryDependencies.get(i);
            def coordinates = mavenCoordinates.findAll { MavenCoordinatesImpl it ->
                it.compareWithoutVersion(androidLibrary.getResolvedCoordinates())
            }
            if (coordinates.size() > 0) {
                i--;
                newLibraryDependencies.remove(androidLibrary);
                flatNeedShadeLibraryDependencies(new LinkedList<AndroidLibrary>(androidLibrary.getLibraryDependencies().asList()), mavenCoordinates, newLocalJars)
                androidLibrary.getJavaDependencies().each {
                    flatJavaLibrary(it, newLocalJars);
                }
                androidLibrary.getLocalJars().each { localJarFile ->
                    MavenCoordinates coord = JarDependency.getCoordForLocalJar(localJarFile);
                    boolean provided = false
                    JarDependency localJar
                    localJar = new JarDependency(
                            localJarFile,
                            ImmutableList.of(),
                            coord,
                            null,
                            provided);
                    newLocalJars.add(localJar)
                }
            }
        }
    }

    /**
     * 将javaLibrary中的Jar依赖及其关联依赖展开并放至javaLibraries
     *
     * @param javaLibrary
     * @param javaLibraries
     * @return
     */
    private static List<? extends JavaLibrary> flatJavaLibrary(
            JavaLibrary javaLibrary,
            List<? extends JavaLibrary> javaLibraries) {
        javaLibraries.add(javaLibrary)
        if (javaLibrary.getDependencies().size() > 0) {
            javaLibrary.getDependencies().each {
                flatJavaLibrary(it, javaLibraries);
            }
        }
        return javaLibraries;
    }

    /**
     * @see com.android.build.gradle.internal.DependencyManager
     *
     * @param resolvedArtifact
     * @return
     */
    @NonNull
    private static MavenCoordinates createMavenCoordinates(
            @NonNull ResolvedArtifact resolvedArtifact) {
        return new MavenCoordinatesImpl(
                resolvedArtifact.getModuleVersion().getId().getGroup(),
                resolvedArtifact.getModuleVersion().getId().getName(),
                resolvedArtifact.getModuleVersion().getId().getVersion(),
                resolvedArtifact.getExtension(),
                resolvedArtifact.getClassifier());
    }

    /**
     * 查找当前Variant相关的所有的Shade依赖.
     * @param project
     * @param variantData
     * @return
     */
    static Set<MavenCoordinates> findVariantShadeDependenciesMavenCoordinates(
            Project project,
            LibraryVariantData variantData) {
        Set<ResolvedArtifact> resolvedArtifacts = findVariantShadeDependenciesArtifacts(project, variantData)
        Set<MavenCoordinates> mavenCoordinates = new LinkedHashSet<>();
        resolvedArtifacts.each {
            mavenCoordinates.add(createMavenCoordinates(it))
        }
        return mavenCoordinates
    }

    /**
     * 查找当前Variant相关的所有的Shade依赖.
     *
     * @param project
     * @param libraryVariantData
     * @return
     */
    static Set<ResolvedArtifact> findVariantShadeDependenciesArtifacts(
            Project project,
            LibraryVariantData libraryVariantData) {
        Set<Configuration> configurations = getVariantShadeConfigurations(project.configurations, libraryVariantData.variantConfiguration);
        Set<ResolvedArtifact> resolvedArtifacts = new LinkedHashSet<>()
        configurations.each { shadeConfiguration ->
            if (DEBUG) {
                println("Find configuration " + shadeConfiguration.getName())
                DependencySet dependencies = shadeConfiguration.dependencies
                dependencies.each { Dependency dependency ->
                    println dependency
                }
                println "Shade Configuration Files: "
                shadeConfiguration.files.each {
                    println it
                }
                println ""
            }
            resolvedArtifacts.addAll(shadeConfiguration.resolvedConfiguration.resolvedArtifacts)
        }
        if (DEBUG) {
            println "All Shade Files of Current Variant : "
            resolvedArtifacts.each {
                println it
            }
            println ""
        }
        return resolvedArtifacts
    }

    /**
     * 从configurationContainer中查询variant指定的相关所有shade配置.
     *
     * @param configurationContainer
     * @param variantConfiguration
     * @return
     */
    private static Set<Configuration> getVariantShadeConfigurations(
            ConfigurationContainer configurationContainer,
            GradleVariantConfiguration variantConfiguration) {
        def sourceProviders = variantConfiguration.getSortedSourceProviders()
        Set<Configuration> configurations = new LinkedHashSet<>(sourceProviders.size(), 1);
        sourceProviders.each {
            def shadeConfigurationName = ShadeExtension.getShadeConfigurationName(it.getName());
            configurations.add(configurationContainer.findByName(shadeConfigurationName))
        }
        return configurations
    }
}

