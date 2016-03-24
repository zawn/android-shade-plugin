package com.house365.build.task;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.internal.dependency.ManifestDependencyImpl;
import com.android.build.gradle.internal.dsl.CoreBuildType;
import com.android.build.gradle.internal.dsl.CoreProductFlavor;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.variant.BaseVariantOutputData;
import com.android.build.gradle.internal.variant.LibraryVariantData;
import com.android.build.gradle.tasks.ProcessManifest;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.core.VariantConfiguration;
import com.android.builder.dependency.LibraryDependency;
import com.android.builder.dependency.ManifestDependency;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.MergingReport;
import com.android.utils.ILogger;
import com.android.utils.Pair;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodInvokeUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 用于合并AAR包中的Manifest文件.
 * <p/>
 * Created by ZhangZhenli on 2016/3/24.
 */
public class LibraryManifestMergeTask extends DefaultTask {

    public LibraryVariantData variantData;

    private ILogger mLogger;
    private VariantConfiguration<CoreBuildType, CoreProductFlavor, CoreProductFlavor>
            variantConfiguration;
    private ProcessManifest processManifest;
    private AndroidBuilder builder;
    public List<ManifestDependencyImpl> libraries;

    @TaskAction
    public void mergeLibraryManifest() {
        System.out.println("LibraryManifestMergeTask.mergeLibraryManifest");
        VariantScope scope = variantData.getScope();
        BaseVariantOutputData variantOutputData = scope.getVariantData().getOutputs().get(0);
        processManifest = (ProcessManifest) variantOutputData.manifestProcessorTask;
        File aaptManifestFile = processManifest.getAaptFriendlyManifestOutputFile();
        String aaptFriendlyManifestOutputFilePath =
                aaptManifestFile == null ? null : aaptManifestFile.getAbsolutePath();
        builder = scope.getGlobalScope().getAndroidBuilder();
        variantConfiguration = scope.getVariantConfiguration();
        mLogger = builder.getLogger();

        mergeManifests(
                processManifest.getMainManifest(),
                processManifest.getManifestOverlays(),
                Collections.<ManifestDependency>emptyList(),
                processManifest.getPackageOverride(),
                processManifest.getVersionCode(),
                processManifest.getVersionName(),
                processManifest.getMinSdkVersion(),
                processManifest.getTargetSdkVersion(),
                processManifest.getMaxSdkVersion(),
                processManifest.getManifestOutputFile().getAbsolutePath(),
                aaptFriendlyManifestOutputFilePath,
                null /* outInstantRunManifestLocation */,
                ManifestMerger2.MergeType.LIBRARY,
                variantConfiguration.getManifestPlaceholders(),
                Collections.<ManifestMerger2.Invoker.Feature>emptyList(),
                processManifest.getReportFile());

    }

    /**
     * Invoke the Manifest Merger version 2.
     * <p/>
     * 拷贝自{@link AndroidBuilder}基本保留原有实现,仅通过反射为manifestMergerInvoker注入libraries.
     *
     * @see AndroidBuilder#mergeManifests(File, List, List, String, int, String, String, String, Integer, String, String, String, ManifestMerger2.MergeType, Map, List, File)
     */
    private void mergeManifests(
            @NonNull File mainManifest,
            @NonNull List<File> manifestOverlays,
            @NonNull List<? extends ManifestDependency> libraries,
            String packageOverride,
            int versionCode,
            String versionName,
            @Nullable String minSdkVersion,
            @Nullable String targetSdkVersion,
            @Nullable Integer maxSdkVersion,
            @NonNull String outManifestLocation,
            @Nullable String outAaptSafeManifestLocation,
            @Nullable String outInstantRunManifestLocation,
            ManifestMerger2.MergeType mergeType,
            Map<String, Object> placeHolders,
            List<ManifestMerger2.Invoker.Feature> optionalFeatures,
            @Nullable File reportFile) {

        try {

            ManifestMerger2.Invoker manifestMergerInvoker =
                    ManifestMerger2.newMerger(mainManifest, mLogger, mergeType)
                            .setPlaceHolderValues(placeHolders)
                            .addFlavorAndBuildTypeManifests(
                                    manifestOverlays.toArray(new File[manifestOverlays.size()]))
                            .addLibraryManifests(collectLibraries(libraries));


            try {
                ImmutableList.Builder<Pair<String, File>> libraryFilesBuilder = (ImmutableList.Builder<Pair<String, File>>) FieldUtils.readField(manifestMergerInvoker, "mLibraryFilesBuilder", true);
                ImmutableList<Pair<String, File>> elements = collectLibraries(this.libraries);
                libraryFilesBuilder.addAll(elements);
                System.out.println("libraries:\n" + this.libraries.toString());
                for (Pair<String, File> pair : elements) {
                    System.out.println(pair.toString());
                }

                System.out.println(libraryFilesBuilder.build());
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
            System.out.println("-------------------------------------456321");
            manifestMergerInvoker.withFeatures(optionalFeatures.toArray(
                    new ManifestMerger2.Invoker.Feature[optionalFeatures.size()]))
                    .setMergeReportFile(reportFile);

            if (mergeType == ManifestMerger2.MergeType.APPLICATION) {
                manifestMergerInvoker.withFeatures(ManifestMerger2.Invoker.Feature.REMOVE_TOOLS_DECLARATIONS);
            }

            //noinspection VariableNotUsedInsideIf
            if (outAaptSafeManifestLocation != null) {
                manifestMergerInvoker.withFeatures(ManifestMerger2.Invoker.Feature.MAKE_AAPT_SAFE);
            }

            setInjectableValues(manifestMergerInvoker,
                    packageOverride, versionCode, versionName,
                    minSdkVersion, targetSdkVersion, maxSdkVersion);

            MergingReport mergingReport = manifestMergerInvoker.merge();
            mLogger.info("Merging result:" + mergingReport.getResult());
            switch (mergingReport.getResult()) {
                case WARNING:
                    mergingReport.log(mLogger);
                    // fall through since these are just warnings.
                case SUCCESS:
                    String xmlDocument = mergingReport.getMergedDocument(
                            MergingReport.MergedManifestKind.MERGED);
                    String annotatedDocument = mergingReport.getMergedDocument(
                            MergingReport.MergedManifestKind.BLAME);
                    if (annotatedDocument != null) {
                        mLogger.verbose(annotatedDocument);
                    }
                    save(xmlDocument, new File(outManifestLocation));
                    mLogger.info("Merged manifest saved to " + outManifestLocation);

                    if (outAaptSafeManifestLocation != null) {
                        save(mergingReport.getMergedDocument(MergingReport.MergedManifestKind.AAPT_SAFE),
                                new File(outAaptSafeManifestLocation));
                    }

                    if (outInstantRunManifestLocation != null) {
                        String instantRunMergedManifest = mergingReport.getMergedDocument(
                                MergingReport.MergedManifestKind.INSTANT_RUN);
                        if (instantRunMergedManifest != null) {
                            save(instantRunMergedManifest, new File(outInstantRunManifestLocation));
                        }
                    }
                    break;
                case ERROR:
                    mergingReport.log(mLogger);
                    throw new RuntimeException(mergingReport.getReportString());
                default:
                    throw new RuntimeException("Unhandled result type : "
                            + mergingReport.getResult());
            }
        } catch (ManifestMerger2.MergeFailureException e) {
            // TODO: unacceptable.
            throw new RuntimeException(e);
        }
    }

    /**
     * Saves the {@link com.android.manifmerger.XmlDocument} to a file in UTF-8 encoding.
     *
     * @param xmlDocument xml document to save.
     * @param out         file to save to.
     */
    private static void save(String xmlDocument, File out) {
        try {
            Files.createParentDirs(out);
            Files.write(xmlDocument, out, Charsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Collect the list of libraries' manifest files.
     *
     * @param libraries declared dependencies
     * @return a list of files and names for the libraries' manifest files.
     */
    private static ImmutableList<Pair<String, File>> collectLibraries(
            List<? extends ManifestDependency> libraries) {
        Object collectLibraries = null;
        try {
            collectLibraries = MethodInvokeUtils.invokeStaticMethod(AndroidBuilder.class, "collectLibraries", libraries);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return (ImmutableList<Pair<String, File>>) collectLibraries;
    }

    /**
     * Sets the {@link com.android.manifmerger.ManifestMerger2.SystemProperty} that can be injected
     * in the manifest file.
     */
    private static void setInjectableValues(
            ManifestMerger2.Invoker<?> invoker,
            String packageOverride,
            int versionCode,
            String versionName,
            @Nullable String minSdkVersion,
            @Nullable String targetSdkVersion,
            @Nullable Integer maxSdkVersion) {
        try {
            MethodInvokeUtils.invokeStaticMethod(AndroidBuilder.class,
                    "setInjectableValues",
                    invoker, packageOverride, versionCode, versionName,
                    minSdkVersion, targetSdkVersion, maxSdkVersion);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    public List<ManifestDependencyImpl> getLibraries() {
        return libraries;
    }

    public void setLibraries(List<ManifestDependencyImpl> libraries) {
        this.libraries = libraries;
    }

    public void setVariantData(LibraryVariantData variantData) {
        this.variantData = variantData;
    }

    public LibraryVariantData getVariantData() {
        return variantData;
    }

    @NonNull
    public static List<ManifestDependencyImpl> getManifestDependencies(
            List<LibraryDependency> libraries) {

        List<ManifestDependencyImpl> list = Lists.newArrayListWithCapacity(libraries.size());

        for (LibraryDependency lib : libraries) {
            // get the dependencies
            List<ManifestDependencyImpl> children =
                    getManifestDependencies(lib.getDependencies());
            list.add(new ManifestDependencyImpl(lib.getName(), lib.getManifest(), children));
        }

        return list;
    }
}
