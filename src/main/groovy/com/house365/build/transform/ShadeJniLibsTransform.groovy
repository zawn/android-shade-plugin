/*
 * Copyright (C) 2015 House365. All rights reserved.
 */

package com.house365.build.transform

import com.android.SdkConstants
import com.android.annotations.NonNull
import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.OriginalStream
import com.android.build.gradle.internal.tasks.LibraryJniLibsTransform
import com.android.build.gradle.internal.variant.LibraryVariantData
import com.android.builder.dependency.level2.AndroidDependency
import com.android.utils.FileUtils
import com.google.common.base.Joiner
import com.google.common.collect.Lists
import com.google.common.io.ByteStreams
import com.google.common.io.Files
import com.house365.build.ShadeTaskManager
import org.apache.commons.lang3.reflect.MethodInvokeUtils
import org.gradle.tooling.exceptions.UnsupportedBuildArgumentException

import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

import static com.android.SdkConstants.FD_JNI

/**
 * A transform that merges all the incoming inputs (folders and jars) into a single jar in a
 * single combined output.
 * <p/>
 * This only packages the class files. It ignores other files.
 */
public class ShadeJniLibsTransform extends LibraryJniLibsTransform {

    private ShadeTaskManager shadeTaskManager
    private LibraryVariantData variantData
    private final Pattern pattern;
    private final File jniLibsFolder;

    ShadeJniLibsTransform(ShadeTaskManager shadeTaskManager, LibraryVariantData variantData) {
        super(new File(variantData.getScope().getBaseBundleDir(), FD_JNI))
        jniLibsFolder = new File(variantData.getScope().getBaseBundleDir(), FD_JNI);
        this.variantData = variantData
        this.shadeTaskManager = shadeTaskManager
        StringBuilder stringBuilder = new StringBuilder()
        if (this.variantData.getVariantConfiguration().getSupportedAbis() != null) {
            if (this.variantData.getVariantConfiguration().getSupportedAbis().size() == 0) {
                throw new UnsupportedBuildArgumentException("please keep at least one abi in ndk.abiFilters.")
            }
            for (String abi : this.variantData.getVariantConfiguration().getSupportedAbis()) {
                stringBuilder.append("(").append(Pattern.quote(abi)).append(")")
            }
            this.pattern = Pattern.compile("[" + stringBuilder + "]+/[^/]+\\.so");
        } else {
            throw new UnsupportedBuildArgumentException("please configure abiFilters.")
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "shadeJniLibs";
    }

    public void transform(@NonNull TransformInvocation invocation)
            throws TransformException, InterruptedException, IOException {
        List<AndroidDependency> libraryDependencies = shadeTaskManager.getVariantShadeLibraries(variantData.getName())
        Set<String> filters = variantData.getVariantConfiguration().getSupportedAbis();
        Map<String, AndroidDependency> jniDirNames = new HashMap<>();
        for (AndroidDependency dependency : libraryDependencies) {
            String name = getUniqueInputName(dependency.getJniFolder());
            boolean hasAbi = false
            boolean missingAbi = false
            for (String abi : filters) {
                if (FileUtils.join(dependency.getJniFolder(), abi).exists()) {
                    hasAbi = true;
                } else {
                    missingAbi = true;
                }
            }
            if (hasAbi && missingAbi) {
                throw new UnsupportedOperationException("Can not find all " + filters.join(",") + " abis in shade dependency:" + dependency.getCoordinates() + ",you may need to reconfigure ndk abiFilters")
            } else if (hasAbi) {
                jniDirNames.put(name, dependency);
            }
        }
        for (TransformInput input : invocation.getReferencedInputs()) {
            for (JarInput jarInput : input.getJarInputs()) {
                if (jniDirNames.containsKey(jarInput.getName())) {
                    jniDirNames.remove(jarInput.getName())
                    copyFromJar(jarInput.getFile())
                }
            }

            for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                if (jniDirNames.containsKey(directoryInput.getName())) {
                    jniDirNames.remove(directoryInput.getName())
                    copyFromFolder(directoryInput.getFile())
                }
            }
        }
        if (!jniDirNames.isEmpty()) {
            for (AndroidDependency androidDependency : jniDirNames.values()) {
                copyFromFolder(androidDependency.getJniFolder())
            }
        }
    }

    private void copyFromFolder(@NonNull File rootDirectory) throws IOException {
        copyFromFolder(rootDirectory, Lists.<String> newArrayListWithCapacity(3));
    }

    private void copyFromFolder(@NonNull File from, @NonNull List<String> pathSegments)
            throws IOException {
        File[] children = from.listFiles(new FilenameFilter() {
            @Override
            boolean accept(File file, String name) {
                return file.isDirectory() || name.endsWith(SdkConstants.DOT_NATIVE_LIBS)
            }
        });

        if (children != null) {
            for (File child : children) {
                pathSegments.add(child.getName());
                if (child.isDirectory()) {
                    copyFromFolder(child, pathSegments);
                } else if (child.isFile()) {
                    if (pattern.matcher(Joiner.on('/').join(pathSegments)).matches()) {
                        // copy the file. However we do want to skip the first segment ('lib') here
                        // since the 'jni' folder is representing the same concept.
                        List<String> list = pathSegments.subList(0, 2)
                        File to = FileUtils.join(jniLibsFolder, list);
                        FileUtils.mkdirs(to.getParentFile());
                        Files.copy(child, to);
                    }
                }

                pathSegments.remove(pathSegments.size() - 1);
            }
        }

    }

    private void copyFromJar(@NonNull File jarFile) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ZipFile zipFile = new ZipFile(jarFile)

        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();

            String entryPath = entry.getName();

            if (!pattern.matcher(entryPath).matches()) {
                continue;
            }

            // read the content.
            buffer.reset();
            ByteStreams.copy(zipFile.getInputStream(entry), buffer);

            // get the output file and write to it.
            final File to = computeFile(jniLibsFolder, entryPath);
            FileUtils.mkdirs(to.getParentFile());
            Files.write(buffer.toByteArray(), to);
        }
    }

    /**
     * @see OriginalStream#getUniqueInputName(File file)
     * @param file
     * @return
     */
    private static String getUniqueInputName(@NonNull File file) {
        return MethodInvokeUtils.invokeStaticMethod(OriginalStream.class, "getUniqueInputName", file)
    }
}

