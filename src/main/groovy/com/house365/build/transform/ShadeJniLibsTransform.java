/*
 * Copyright (C) 2015 House365. All rights reserved.
 */

package com.house365.build.transform;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.tasks.TaskAction;
import org.gradle.tooling.exceptions.UnsupportedBuildArgumentException;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.publishing.AndroidArtifacts;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.transforms.LibraryJniLibsTransform;
import com.android.utils.FileUtils;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.house365.build.ShadeTaskManager;

import static com.android.SdkConstants.FD_JNI;

/**
 * A Transforms that takes the project/project local streams for native libs and processes and
 * combines them, and put them in the bundle folder under jni/
 * <p/>
 * This only packages the class files. It ignores other files.
 */
public class ShadeJniLibsTransform extends LibraryJniLibsTransform {

    private ShadeTaskManager shadeTaskManager;
    private final Pattern pattern;
    private final File jniLibsFolder;
    private VariantScope variantScope;

    public ShadeJniLibsTransform(ShadeTaskManager shadeTaskManager,
                                 VariantScope variantScope) {
        super("syncShadeJniLibs", new File(variantScope.getBaseBundleDir(), FD_JNI), ImmutableSet.of(QualifiedContent.Scope.EXTERNAL_LIBRARIES));
        this.variantScope = variantScope;
        jniLibsFolder = new File(variantScope.getBaseBundleDir(), FD_JNI);
        this.shadeTaskManager = shadeTaskManager;
        StringBuilder stringBuilder = new StringBuilder();
        if (this.variantScope.getVariantConfiguration().getSupportedAbis() != null) {
            if (this.variantScope.getVariantConfiguration().getSupportedAbis().size() == 0) {
                throw new UnsupportedBuildArgumentException("please keep at least one abi in ndk.abiFilters.");
            }
            for (String abi : this.variantScope.getVariantConfiguration().getSupportedAbis()) {
                stringBuilder.append("(").append(Pattern.quote(abi)).append(")");
            }
            this.pattern = Pattern.compile("[" + stringBuilder + "]+/[^/]+\\.so");
        } else {
            throw new UnsupportedBuildArgumentException("please configure abiFilters.");
        }
    }

    @NonNull
    @Override
    public Set<? super QualifiedContent.Scope> getReferencedScopes() {
        return TransformManager.SCOPE_FULL_PROJECT;
    }

    @NonNull
    @Override
    public String getName() {
        return "shadeJniLibs";
    }

    @TaskAction
    public void transform(@NonNull TransformInvocation invocation)
            throws IOException {
        ArtifactCollection artifacts = shadeTaskManager.getShadeArtifactCollection(variantScope, AndroidArtifacts.ArtifactType.JNI);
        Set<String> filters = variantScope.getVariantConfiguration().getSupportedAbis();
        ArrayList<File> jniDirNames = new ArrayList<>();
        for (File file : artifacts.getArtifactFiles()) {
            boolean hasAbi = false;
            boolean missingAbi = false;
            for (String abi : filters) {
                if (FileUtils.join(file, abi).exists()) {
                    hasAbi = true;
                } else {
                    missingAbi = true;
                }
            }
            if (hasAbi && missingAbi) {
                throw new UnsupportedOperationException("Can not find all " + Joiner.on(',').join(filters) + " abis in shade dependency:" + file + ",you may need to reconfigure ndk abiFilters");
            } else if (hasAbi) {
                jniDirNames.add(file);
            }
        }

        if (!jniDirNames.isEmpty()) {
            for (File file : jniDirNames) {
                copyFromFolder(file);
            }
        }
    }

    private void copyFromFolder(@NonNull File rootDirectory) throws IOException {
        copyFromFolder(rootDirectory, Lists.<String>newArrayListWithCapacity(3));
    }

    private void copyFromFolder(@NonNull File from, @NonNull List<String> pathSegments)
            throws IOException {
        File[] children = from.listFiles((file, name) ->
                file.isDirectory() || name.endsWith(SdkConstants.DOT_NATIVE_LIBS)
        );

        if (children != null) {
            for (File child : children) {
                pathSegments.add(child.getName());
                if (child.isDirectory()) {
                    copyFromFolder(child, pathSegments);
                } else if (child.isFile()) {
                    if (pattern.matcher(Joiner.on('/').join(pathSegments)).matches()) {
                        // copy the file. However we do want to skip the first segment ('lib') here
                        // since the 'jni' folder is representing the same concept.
                        List<String> list = pathSegments.subList(0, 2);
                        File to = FileUtils.join(jniLibsFolder, list);
                        FileUtils.mkdirs(to.getParentFile());
                        Files.copy(child, to);
                    }
                }

                pathSegments.remove(pathSegments.size() - 1);
            }
        }
    }
}

