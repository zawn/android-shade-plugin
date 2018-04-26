/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.house365.build.transform;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.gradle.api.file.FileCollection;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.QualifiedContent.Scope;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.transforms.LibraryBaseTransform;
import com.android.builder.packaging.JarMerger;
import com.android.builder.packaging.TypedefRemover;
import com.android.utils.FileUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import static org.apache.commons.lang3.reflect.FieldUtils.readField;

/**
 * 部分参考{@link com.android.build.gradle.internal.transforms.LibraryAarJarsTransform}
 * <p>
 * A Transforms that takes the project/project local streams for CLASSES and RESOURCES,
 * and processes and combines them, and put them in the bundle folder.
 * <p>
 * This creates a main jar with the classes from the main scope, and all the java resources, and
 * a set of local jars (which are mostly the same as before except without the resources).
 * This is used to package the AAR.
 * <p>
 * Regarding Streams, this is a no-op transform as it does not write any output to any stream. It
 * uses secondary outputs to write directly into the bundle folder.
 */
public class LibraryAarJarsTransform extends LibraryBaseTransform {

    public LibraryAarJarsTransform(
            LibraryBaseTransform baseTransform
    ) throws IllegalAccessException {

        super((File) readField(baseTransform, "mainClassLocation", true),
                (File) readField(baseTransform, "localJarsLocation", true),
                (FileCollection) readField(baseTransform, "typedefRecipe", true),
                (String) readField(baseTransform, "packagePath", true),
                (boolean) readField(baseTransform, "packageBuildConfig", true)
        );
    }

    public LibraryAarJarsTransform(
            @NonNull File mainClassLocation,
            @NonNull File localJarsLocation,
            @Nullable FileCollection typedefRecipe,
            @NonNull String packageName,
            boolean packageBuildConfig) {
        super(mainClassLocation, localJarsLocation, typedefRecipe, packageName, packageBuildConfig);
    }

    @NonNull
    @Override
    public String getName() {
        return "syncLibJars";
    }

    @NonNull
    @Override
    public Set<? super Scope> getReferencedScopes() {
        return TransformManager.SCOPE_FULL_LIBRARY_WITH_LOCAL_JARS;
    }

    @Override
    public boolean isIncremental() {
        // TODO make incremental
        return false;
    }

    @NonNull
    @Override
    public Collection<File> getSecondaryFileOutputs() {
        return ImmutableList.of(mainClassLocation);
    }

    @Override
    public void transform(@NonNull TransformInvocation invocation)
            throws IOException, TransformException, InterruptedException {
        // non incremental transform, need to clear out outputs.
        // main class jar will get rewritten, just delete local jar folder content.
        if (localJarsLocation != null) {
            FileUtils.deleteDirectoryContents(localJarsLocation);
        }
        if (typedefRecipe != null && !typedefRecipe.getSingleFile().exists()) {
            throw new IllegalStateException("Type def recipe not found: " + typedefRecipe);
        }

        List<Pattern> patterns = computeExcludeList();

        // first look for what inputs we have. There shouldn't be that many inputs so it should
        // be quick and it'll allow us to minimize jar merging if we don't have to.
        List<QualifiedContent> mainScope = Lists.newArrayList();
        List<QualifiedContent> localJarScope = Lists.newArrayList();

        for (TransformInput input : invocation.getReferencedInputs()) {
            for (QualifiedContent qualifiedContent : Iterables.concat(
                    input.getJarInputs(), input.getDirectoryInputs())) {
                if (qualifiedContent.getScopes().contains(Scope.PROJECT)) {
                    // even if the scope contains both project + local jar, we treat this as main
                    // scope.
                    mainScope.add(qualifiedContent);
                } else {
                    localJarScope.add(qualifiedContent);
                }
            }
        }

        // process main scope.
        if (mainScope.isEmpty()) {
            throw new RuntimeException("Empty Main scope for " + getName());
        }

        mergeInputsToLocation(
                mainScope,
                mainClassLocation,
                false,
                archivePath -> checkEntry(patterns, archivePath),
                typedefRecipe != null
                        ? new TypedefRemover().setTypedefFile(typedefRecipe.getSingleFile())
                        : null);

        // process local scope
        FileUtils.deleteDirectoryContents(localJarsLocation);
        processLocalJars(localJarScope);
    }

    protected void processLocalJars(@NonNull List<QualifiedContent> qualifiedContentList)
            throws IOException {

        // first copy the jars (almost) as is, and remove them from the list.
        // then we'll make a single jars that contains all the folders (though it's unlikely to
        // happen)
        // Note that we do need to remove the resources from the jars since they have been merged
        // somewhere else.
        // TODO: maybe do the folders separately to handle incremental?

        Iterator<QualifiedContent> iterator = qualifiedContentList.iterator();

        while (iterator.hasNext()) {
            QualifiedContent content = iterator.next();
            if (content instanceof JarInput) {
                // we need to copy the jars but only take the class files as the resources have
                // been merged into the main jar.
                File from = content.getFile();
                File to = new File(localJarsLocation, from.getName());
//                copyJarWithContentFilter(from, to, ZipEntryFilter.CLASSES_ONLY);
                FileUtils.copyFile(from, to);
                iterator.remove();
            }
        }

        // now handle the folders.
        if (!qualifiedContentList.isEmpty()) {
            try (JarMerger jarMerger =
                         new JarMerger(
                                 new File(localJarsLocation, "otherclasses.jar").toPath(),
                                 null)) {
                for (QualifiedContent content : qualifiedContentList) {
                    jarMerger.addDirectory(content.getFile().toPath());
                }
            }
        }
    }
}
