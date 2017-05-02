package com.house365.build.gradle.tasks;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.internal.dsl.CoreBuildType;
import com.android.build.gradle.internal.dsl.CoreProductFlavor;
import com.android.build.gradle.internal.scope.ConventionMappingHelper;
import com.android.build.gradle.internal.scope.TaskConfigAction;
import com.android.build.gradle.internal.scope.VariantOutputScope;
import com.android.build.gradle.internal.variant.ApkVariantOutputData;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.internal.variant.BaseVariantOutputData;
import com.android.build.gradle.tasks.MergeManifests;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.core.VariantConfiguration;
import com.android.builder.model.ApiVersion;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.ManifestProvider;
import com.android.manifmerger.ManifestSystemProperty;
import com.android.manifmerger.MergingReport;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodInvokeUtils;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Created by zhangzhenli on 2017/4/26.
 */
public class MergeShadeManifests extends MergeManifests {
    @Override
    protected void doFullTaskAction() {
        File aaptManifestFile = getAaptFriendlyManifestOutputFile();
        String aaptFriendlyManifestOutputFilePath =
                aaptManifestFile == null ? null : aaptManifestFile.getAbsolutePath();
        mergeManifestsForShadeLibrary(
                getMainManifest(),
                getManifestOverlays(),
                getProviders(),
                getPackageOverride(),
                getVersionCode(),
                getVersionName(),
                getMinSdkVersion(),
                getTargetSdkVersion(),
                getMaxSdkVersion(),
                getManifestOutputFile().getAbsolutePath(),
                aaptFriendlyManifestOutputFilePath,
                getInstantRunManifestOutputFile().getAbsolutePath(),
                ManifestMerger2.MergeType.LIBRARY,
                getVariantConfiguration().getManifestPlaceholders(),
                getOptionalFeatures(),
                getReportFile());
    }

    /**
     * 覆盖默认的{@link ConfigAction#getName()}方法,为Shader的MergeManifests Task 配置正确的名称.
     * <p>
     * Created by zhangzhenli on 2017/2/13.
     */
    public static class ConfigAction implements TaskConfigAction<MergeShadeManifests> {

        private final VariantOutputScope scope;
        private final List<ManifestMerger2.Invoker.Feature> optionalFeatures;

        public ConfigAction(VariantOutputScope scope, List<ManifestMerger2.Invoker.Feature> optionalFeatures) {
            this.scope = scope;
            this.optionalFeatures = optionalFeatures;
        }

        @Override
        public String getName() {
            return scope.getTaskName("process", "ShaderManifest");
        }

        @NonNull
        @Override
        public Class<MergeShadeManifests> getType() {
            return MergeShadeManifests.class;
        }

        @Override
        public void execute(@NonNull MergeShadeManifests processManifestTask) {
            BaseVariantOutputData variantOutputData = scope.getVariantOutputData();

            final BaseVariantData<? extends BaseVariantOutputData> variantData =
                    scope.getVariantScope().getVariantData();
            final VariantConfiguration<CoreBuildType, CoreProductFlavor, CoreProductFlavor> config =
                    variantData.getVariantConfiguration();


            processManifestTask.setAndroidBuilder(scope.getGlobalScope().getAndroidBuilder());
            processManifestTask.setVariantName(config.getFullName());

            processManifestTask.setVariantConfiguration(config);
            if (variantOutputData instanceof ApkVariantOutputData) {
                processManifestTask.setVariantOutputData((ApkVariantOutputData) variantOutputData);
            }

            ConventionMappingHelper.map(processManifestTask, "providers",
                    () -> {
                        List<ManifestProvider> manifests = Lists.newArrayList(
                                config.getFlatPackageAndroidLibraries());
                        manifests.addAll(config.getFlatAndroidAtomsDependencies());

                        if (scope.getVariantScope().getMicroApkTask() != null &&
                                variantData.getVariantConfiguration().getBuildType().
                                        isEmbedMicroApp()) {
                            manifests.add(new MergeShadeManifests.ConfigAction.ManifestProviderImpl(
                                    scope.getVariantScope().getMicroApkManifestFile(),
                                    "Wear App sub-manifest"));
                        }

                        if (scope.getCompatibleScreensManifestTask() != null) {
                            manifests.add(new MergeShadeManifests.ConfigAction.ManifestProviderImpl(
                                    scope.getCompatibleScreensManifestFile(),
                                    "Compatible-Screens sub-manifest"));
                        }

                        return manifests;
                    });

            ConventionMappingHelper.map(processManifestTask, "minSdkVersion",
                    new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            if (scope.getGlobalScope().getAndroidBuilder().isPreviewTarget()) {
                                return scope.getGlobalScope().getAndroidBuilder()
                                        .getTargetCodename();
                            }

                            ApiVersion minSdk = config.getMergedFlavor().getMinSdkVersion();
                            return minSdk == null ? null : minSdk.getApiString();
                        }
                    });

            ConventionMappingHelper.map(processManifestTask, "targetSdkVersion",
                    new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            if (scope.getGlobalScope().getAndroidBuilder().isPreviewTarget()) {
                                return scope.getGlobalScope().getAndroidBuilder()
                                        .getTargetCodename();
                            }
                            ApiVersion targetSdk = config.getMergedFlavor().getTargetSdkVersion();
                            return targetSdk == null ? null : targetSdk.getApiString();
                        }
                    });

            ConventionMappingHelper.map(processManifestTask, "maxSdkVersion",
                    new Callable<Integer>() {
                        @Override
                        public Integer call() throws Exception {
                            if (scope.getGlobalScope().getAndroidBuilder().isPreviewTarget()) {
                                return null;
                            }
                            return config.getMergedFlavor().getMaxSdkVersion();
                        }
                    });

            processManifestTask.setManifestOutputFile(scope.getManifestOutputFile());

            processManifestTask.setAaptFriendlyManifestOutputFile(
                    scope.getVariantScope().getAaptFriendlyManifestOutputFile());

            processManifestTask.setInstantRunManifestOutputFile(
                    scope.getVariantScope().getInstantRunManifestOutputFile());

            processManifestTask.setReportFile(scope.getVariantScope().getManifestReportFile());
            List<ManifestMerger2.Invoker.Feature> optionalFeatures = processManifestTask.getOptionalFeatures();
            try {
                FieldUtils.getField(MergeShadeManifests.class, "optionalFeatures", true).set(processManifestTask, this.optionalFeatures);
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
            variantOutputData.manifestProcessorTask = processManifestTask;
        }

        /**
         * Implementation of AndroidBundle that only contains a manifest.
         * <p>
         * This is used to pass to the merger manifest snippet that needs to be added during
         * merge.
         */
        private static class ManifestProviderImpl implements ManifestProvider {

            @NonNull
            private final File manifest;

            @NonNull
            private final String name;

            public ManifestProviderImpl(@NonNull File manifest, @NonNull String name) {
                this.manifest = manifest;
                this.name = name;
            }

            @NonNull
            @Override
            public File getManifest() {
                return manifest;
            }

            @NonNull
            @Override
            public String getName() {
                return name;
            }
        }
    }


    /**
     * Invoke the Manifest Merger version 2.
     *
     * @see AndroidBuilder#mergeManifestsForApplication(File, List, List, String, int, String, String, String, Integer, String, String, String, ManifestMerger2.MergeType, Map, List, File)
     */
    public void mergeManifestsForShadeLibrary(
            @NonNull File mainManifest,
            @NonNull List<File> manifestOverlays,
            @NonNull List<? extends ManifestProvider> dependencies,
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
            @NonNull List<ManifestMerger2.Invoker.Feature> optionalFeatures,
            @Nullable File reportFile) {

        try {

            ManifestMerger2.Invoker manifestMergerInvoker =
                    ManifestMerger2.newMerger(mainManifest, getILogger(), mergeType)
                            .setPlaceHolderValues(placeHolders)
                            .addFlavorAndBuildTypeManifests(
                                    manifestOverlays.toArray(new File[manifestOverlays.size()]))
                            .addManifestProviders(dependencies)
                            .withFeatures(optionalFeatures.toArray(
                                    new ManifestMerger2.Invoker.Feature[optionalFeatures.size()]))
                            .setMergeReportFile(reportFile);

            manifestMergerInvoker.withFeatures(ManifestMerger2.Invoker.Feature.REMOVE_TOOLS_DECLARATIONS);

            //noinspection VariableNotUsedInsideIf
            if (outAaptSafeManifestLocation != null) {
                manifestMergerInvoker.withFeatures(ManifestMerger2.Invoker.Feature.MAKE_AAPT_SAFE);
            }

            setInjectableValues(manifestMergerInvoker,
                    packageOverride, versionCode, versionName,
                    minSdkVersion, targetSdkVersion, maxSdkVersion);

            MergingReport mergingReport = manifestMergerInvoker.merge();
            getILogger().verbose("Merging result: %1$s", mergingReport.getResult());
            switch (mergingReport.getResult()) {
                case WARNING:
                    mergingReport.log(getILogger());
                    // fall through since these are just warnings.
                case SUCCESS:
                    String xmlDocument = mergingReport.getMergedDocument(
                            MergingReport.MergedManifestKind.MERGED);
                    String annotatedDocument = mergingReport.getMergedDocument(
                            MergingReport.MergedManifestKind.BLAME);
                    if (annotatedDocument != null) {
                        getILogger().verbose(annotatedDocument);
                    }
                    save(xmlDocument, new File(outManifestLocation));
                    getILogger().verbose("Merged manifest saved to " + outManifestLocation);

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
                    mergingReport.log(getILogger());
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
     * Sets the {@link ManifestSystemProperty} that can be injected
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
            MethodInvokeUtils.invokeStaticMethod(AndroidBuilder.class, "setInjectableValues",
                    invoker,
                    packageOverride,
                    versionCode,
                    versionName,
                    minSdkVersion,
                    targetSdkVersion,
                    maxSdkVersion);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
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
            MethodInvokeUtils.invokeStaticMethod(AndroidBuilder.class, "save",
                    xmlDocument,
                    out);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }

}
