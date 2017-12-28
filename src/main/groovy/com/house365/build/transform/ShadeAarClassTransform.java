/*
 * Copyright (C) 2015 House365. All rights reserved.
 */

package com.house365.build.transform;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent.ContentType;
import com.android.build.api.transform.QualifiedContent.Scope;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.LibraryExtension;
import com.android.build.gradle.internal.InternalScope;
import com.android.build.gradle.internal.dsl.PackagingOptions;
import com.android.build.gradle.internal.packaging.ParsedPackagingOptions;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.publishing.AndroidArtifacts;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.builder.core.DefaultManifestParser;
import com.android.builder.packaging.JarMerger;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.house365.build.ShadeTaskManager;
import com.house365.build.util.ZipEntryFilterUtil;
import com.tonicsystems.jarjar.classpath.ClassPath;
import com.tonicsystems.jarjar.transform.JarTransformer;
import com.tonicsystems.jarjar.transform.config.RulesFileParser;
import com.tonicsystems.jarjar.transform.jar.DefaultJarProcessor;

import static com.android.utils.FileUtils.deleteIfExists;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A transform that merges all the incoming inputs (folders and jars) into a single jar in a
 * single combined output.
 * <p/>
 * This only packages the class files. It ignores other files.
 */
public class ShadeAarClassTransform extends Transform {
    public static final boolean DEBUG = false;
    private final Logger logger;
    private LibraryExtension libraryExtension;
    private boolean isLibrary = true;
    private Project project;
    private ShadeTaskManager shadeTaskManager;
    private VariantScope variantScope;


    public ShadeAarClassTransform(Project project, LibraryExtension LibraryExtension,
                                  ShadeTaskManager shadeTaskManager) {
        this.shadeTaskManager = shadeTaskManager;
        this.project = project;
        this.libraryExtension = LibraryExtension;
        this.logger = Logging.getLogger(ShadeAarClassTransform.class);
    }

    @NonNull
    @Override
    public String getName() {
        return "shadeAarClass";
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

    @Override
    public Set<ContentType> getOutputTypes() {
        return TransformManager.CONTENT_JARS;
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
    public Set<? super Scope> getReferencedScopes() {
        return ImmutableSet.of(InternalScope.LOCAL_DEPS);
    }

    public void transform(@NonNull TransformInvocation invocation)
            throws TransformException, InterruptedException, IOException {
        variantScope = shadeTaskManager.getCurrentVariant(invocation);
        String variantName = variantScope.getFullVariantName();

        @Nullable TransformOutputProvider outputProvider = invocation.getOutputProvider();
        checkNotNull(outputProvider, "Missing output object for transform " + getName());

        Configuration shadeAarClasspath = project.getConfigurations().maybeCreate(variantName + "ShadeAarClasspath");
        // 该段代码会导致重复解压缩aar,C:\Users\zhang\.gradle\caches\transforms-1\files-1.1\zxing-android-embedded-0.0.4.aar

        ArtifactCollection classesArtifacts = shadeTaskManager.getShadeArtifactCollection(variantScope, AndroidArtifacts.ArtifactType.CLASSES);
        ArtifactCollection manifestArtifacts = shadeTaskManager.getShadeArtifactCollection(variantScope, AndroidArtifacts.ArtifactType.MANIFEST);

        FileCollection aarJars = classesArtifacts.getArtifactFiles();

        File jarFile = outputProvider.getContentLocation("combined-temp", getOutputTypes(), getScopes(),
                Format.JAR);
        File rJarFile = outputProvider.getContentLocation("r", getOutputTypes(), getScopes(),
                Format.JAR);

        Collection<TransformInput> transformInputs = invocation.getInputs();

        PackagingOptions packagingOptions = libraryExtension.getPackagingOptions();
        ParsedPackagingOptions parsedPackagingOptions = new ParsedPackagingOptions(packagingOptions);

        jarMerger(jarFile, transformInputs, aarJars,
                new ZipEntryFilterUtil.JarWhitoutRFilter(parsedPackagingOptions, null));
        jarMerger(rJarFile, transformInputs, null,
                new ZipEntryFilterUtil.JarRFilter(parsedPackagingOptions, null));

        File outJar = outputProvider.getContentLocation("combined", getOutputTypes(), getScopes(),
                Format.JAR);

        // JarJar
        jarjar(variantScope.getVariantData(), manifestArtifacts, jarFile, outJar);
        if (jarFile.exists()) {
            Files.delete(jarFile.toPath());
        }

        /**
         * {@link com.android.build.gradle.internal.transforms.LibraryAarJarsTransform#transform(TransformInvocation)}实现中丢弃了Local Jar中的Res.
         * 该处理方法在部分情况先会导致程序出错,比如在Local Jar中直接添加okhttp的依赖.故添加对Local Jar中的Res的处理.
         */
        File resJarFile = outputProvider.getContentLocation("res", getOutputTypes(), getScopes(),
                Format.JAR);
        jarMerger(resJarFile, invocation.getReferencedInputs(), null,
                new ZipEntryFilterUtil.JarWhitoutRFilter(parsedPackagingOptions, new ZipEntryFilterUtil.NoJavaClassZipFilter(null)));
    }


    public void jarMerger(File jarFile, Collection<TransformInput> inputs,
                          FileCollection needCombineJars,
                          ZipEntryFilterUtil.PackagingFilter filter) throws IOException, TransformException {
        JarMerger jarMerger = new JarMerger(jarFile.toPath(), filter);
        try {
            for (TransformInput input : inputs) {
                for (JarInput jarInput : input.getJarInputs()) {
                    filter.reset(jarInput.getFile());
                    jarMerger.addJar(jarInput.getFile().toPath());
                }

                for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                    filter.reset(directoryInput.getFile());
                    jarMerger.addDirectory(directoryInput.getFile().toPath());
                }
            }
            if (needCombineJars != null)
                for (File file : needCombineJars) {
                    System.out.println("combine jar: " + file);
                    filter.reset(file);
                    jarMerger.addJar(file.toPath());
                }
        } catch (FileNotFoundException e) {
            throw new TransformException(e);
        } catch (IOException e) {
            throw new TransformException(e);
        } finally {
            jarMerger.close();
        }
    }

    /**
     * 重命名被合并的AAR中的R文件引用.
     *
     * @param variantData
     * @param manifests
     * @param inputJar
     * @param outputJar
     */
    public void jarjar(BaseVariantData variantData,
                       ArtifactCollection manifests,
                       File inputJar,
                       File outputJar) throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        String appPackageName = new DefaultManifestParser(variantData.getVariantConfiguration().getMainManifest()).getPackage();
        for (File manifestFile : manifests.getArtifactFiles()) {
            String manifestPackage = new DefaultManifestParser(manifestFile).getPackage();
            System.out.println("Library PackageName:" + manifestPackage);

            String rule = "rule " + manifestPackage + ".R$*  " + appPackageName + ".R$@1";
            System.out.println("   " + rule);
            stringBuilder.append(rule).append("\r\n");
        }
        com.android.utils.FileUtils.mkdirs(outputJar.getParentFile());
        deleteIfExists(outputJar);
        outputJar.createNewFile();
        DefaultJarProcessor processor = new DefaultJarProcessor();
        RulesFileParser.parse(processor, stringBuilder.toString());
        JarTransformer transformer = new JarTransformer(outputJar, processor);
        ClassPath fromClassPath = new ClassPath(project.getRootDir(), Collections.singleton(inputJar));
        transformer.transform(fromClassPath);
    }
}

