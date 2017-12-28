/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.house365.build.gradle.tasks;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.internal.component.local.model.OpaqueComponentArtifactIdentifier;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.internal.core.GradleVariantConfiguration;
import com.android.build.gradle.internal.dependency.ArtifactCollectionWithExtraArtifact.ExtraComponentIdentifier;
import com.android.build.gradle.internal.dsl.CoreBuildType;
import com.android.build.gradle.internal.dsl.CoreProductFlavor;
import com.android.build.gradle.internal.scope.BuildOutput;
import com.android.build.gradle.internal.scope.BuildOutputs;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.OutputScope;
import com.android.build.gradle.internal.scope.TaskConfigAction;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.ApplicationId;
import com.android.build.gradle.internal.tasks.TaskInputHelper;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.internal.variant.FeatureVariantData;
import com.android.build.gradle.internal.variant.TaskContainer;
import com.android.build.gradle.options.ProjectOptions;
import com.android.build.gradle.options.StringOption;
import com.android.build.gradle.tasks.ManifestProcessorTask;
import com.android.build.gradle.tasks.ProcessAndroidResources;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.core.VariantConfiguration;
import com.android.builder.model.ApiVersion;
import com.android.ide.common.build.ApkData;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.ManifestMerger2.Invoker.Feature;
import com.android.manifmerger.ManifestProvider;
import com.android.manifmerger.MergingReport;
import com.android.manifmerger.XmlDocument;
import com.android.utils.FileUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.house365.build.ShadeTaskManager;

import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactScope.MODULE;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.FEATURE_APPLICATION_ID_DECLARATION;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.MANIFEST;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.METADATA_APP_ID_DECLARATION;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.METADATA_FEATURE_MANIFEST;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.METADATA_VALUES;
import static com.android.build.gradle.options.BooleanOption.BUILD_ONLY_TARGET_ABI;

/**
 * 功能与{@link com.android.build.gradle.tasks.MergeManifests}一致,去掉部分逻辑.
 *
 * @see <a href="https://android.googlesource.com/platform/tools/base/+/gradle_3.0.0/build-system/gradle-core/src/main/java/com/android/build/gradle/tasks/MergeManifests.java">MergeManifests.java</a>
 */

/**
 * A task that processes the manifest
 */
@CacheableTask
public class MergeManifests extends ManifestProcessorTask {

    private Supplier<String> minSdkVersion;
    private Supplier<String> targetSdkVersion;
    private Supplier<Integer> maxSdkVersion;
    private VariantConfiguration<CoreBuildType, CoreProductFlavor, CoreProductFlavor>
            variantConfiguration;
    private ArtifactCollection manifests;
    private ArtifactCollection featureManifests;
    private FileCollection microApkManifest;
    //    private FileCollection compatibleScreensManifest;
    private FileCollection packageManifest;
    private List<Feature> optionalFeatures;
    private OutputScope outputScope;

    private Set<String> supportedAbis;
    private String buildTargetAbi;
    private String buildTargetDensity;
    private String featureName;

    @Override
    protected void doFullTaskAction() throws IOException {
        // read the output of the compatible screen manifest.
//        Collection<BuildOutput> compatibleScreenManifests =
//                BuildOutputs.load(
//                        VariantScope.TaskOutputType.COMPATIBLE_SCREEN_MANIFEST,
//                        compatibleScreensManifest);

        String packageOverride;
        if (packageManifest != null && !packageManifest.isEmpty()) {
            packageOverride =
                    ApplicationId.load(packageManifest.getSingleFile()).getApplicationId();
        } else {
            packageOverride = getPackageOverride();
        }

        @Nullable BuildOutput compatibleScreenManifestForSplit;

        List<ApkData> splitsToGenerate =
                ProcessAndroidResources.getApksToGenerate(
                        outputScope, supportedAbis, buildTargetAbi, buildTargetDensity);

        // FIX ME : multi threading.
        for (ApkData apkData : splitsToGenerate) {
//            compatibleScreenManifestForSplit =
//                    OutputScope.getOutput(
//                            compatibleScreenManifests,
//                            VariantScope.TaskOutputType.COMPATIBLE_SCREEN_MANIFEST,
//                            apkData);
            File manifestOutputFile =
                    FileUtils.join(
                            getManifestOutputDirectory(),
                            apkData.getDirName(),
                            SdkConstants.ANDROID_MANIFEST_XML);
            File instantRunManifestOutputFile =
                    FileUtils.join(
                            getInstantRunManifestOutputDirectory(),
                            apkData.getDirName(),
                            SdkConstants.ANDROID_MANIFEST_XML);
            MergingReport mergingReport =
                    getBuilder()
                            .mergeManifestsForApplication(
                                    getMainManifest(),
                                    getManifestOverlays(),
                                    computeFullProviderList(null),
                                    getFeatureName(),
                                    packageOverride,
                                    apkData.getVersionCode(),
                                    apkData.getVersionName(),
                                    getMinSdkVersion(),
                                    getTargetSdkVersion(),
                                    getMaxSdkVersion(),
                                    manifestOutputFile.getAbsolutePath(),
                                    // no aapt friendly merged manifest file necessary for applications.
                                    null /* aaptFriendlyManifestOutputFile */,
                                    instantRunManifestOutputFile.getAbsolutePath(),
                                    ManifestMerger2.MergeType.APPLICATION,
                                    variantConfiguration.getManifestPlaceholders(),
                                    getOptionalFeatures(),
                                    getReportFile());

            XmlDocument mergedXmlDocument =
                    mergingReport.getMergedXmlDocument(MergingReport.MergedManifestKind.MERGED);

            ImmutableMap<String, String> properties =
                    mergedXmlDocument != null
                            ? ImmutableMap.of(
                            "packageId",
                            mergedXmlDocument.getPackageName(),
                            "split",
                            mergedXmlDocument.getSplitName(),
                            SdkConstants.ATTR_MIN_SDK_VERSION,
                            mergedXmlDocument.getMinSdkVersion())
                            : ImmutableMap.of();

            outputScope.addOutputForSplit(
                    VariantScope.TaskOutputType.MERGED_MANIFESTS,
                    apkData,
                    manifestOutputFile,
                    properties);
            outputScope.addOutputForSplit(
                    VariantScope.TaskOutputType.INSTANT_RUN_MERGED_MANIFESTS,
                    apkData,
                    instantRunManifestOutputFile,
                    properties);
        }
        outputScope.save(
                ImmutableList.of(VariantScope.TaskOutputType.MERGED_MANIFESTS),
                getManifestOutputDirectory());
        outputScope.save(
                ImmutableList.of(VariantScope.TaskOutputType.INSTANT_RUN_MERGED_MANIFESTS),
                getInstantRunManifestOutputDirectory());
    }

    @Nullable
    @Override
    @Internal
    public File getAaptFriendlyManifestOutputFile() {
        return null;
    }

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public File getMainManifest() {
        return variantConfiguration.getMainManifest();
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public List<File> getManifestOverlays() {
        return variantConfiguration.getManifestOverlays();
    }

    @Input
    @Optional
    public String getPackageOverride() {
        return variantConfiguration.getIdOverride();
    }

    @Input
    public List<Integer> getVersionCodes() {
        return outputScope
                .getApkDatas()
                .stream()
                .map(ApkData::getVersionCode)
                .collect(Collectors.toList());
    }

    @Input
    @Optional
    public List<String> getVersionNames() {
        return outputScope
                .getApkDatas()
                .stream()
                .map(ApkData::getVersionName)
                .collect(Collectors.toList());
    }

    /**
     * Returns a serialized version of our map of key value pairs for placeholder substitution.
     * <p>
     * This serialized form is only used by gradle to compare past and present tasks to determine
     * whether a task need to be re-run or not.
     */
    @Input
    @Optional
    public String getManifestPlaceholders() {
        return serializeMap(variantConfiguration.getManifestPlaceholders());
    }

    /**
     * Compute the final list of providers based on the manifest file collection and the other
     * providers.
     *
     * @return the list of providers.
     */
    private List<ManifestProvider> computeFullProviderList(
            @Nullable BuildOutput compatibleScreenManifestForSplit) {
        final Set<ResolvedArtifactResult> artifacts = manifests.getArtifacts();
        List<ManifestProvider> providers = Lists.newArrayListWithCapacity(artifacts.size() + 2);

        for (ResolvedArtifactResult artifact : artifacts) {
            providers.add(new ConfigAction.ManifestProviderImpl(
                    artifact.getFile(),
                    getArtifactName(artifact)));
        }

        if (microApkManifest != null) {
            // this is now always present if embedding is enabled, but it doesn't mean
            // anything got embedded so the file may not run (the file path exists and is
            // returned by the FC but the file doesn't exist.
            File microManifest = microApkManifest.getSingleFile();
            if (microManifest.isFile()) {
                providers.add(new ConfigAction.ManifestProviderImpl(
                        microManifest,
                        "Wear App sub-manifest"));
            }
        }

        if (compatibleScreenManifestForSplit != null) {
            providers.add(
                    new ConfigAction.ManifestProviderImpl(
                            compatibleScreenManifestForSplit.getOutputFile(),
                            "Compatible-Screens sub-manifest"));
        }

        if (featureManifests != null) {
            final Set<ResolvedArtifactResult> featureArtifacts = featureManifests.getArtifacts();
            for (ResolvedArtifactResult artifact : featureArtifacts) {
                File directory = artifact.getFile();

                Collection<BuildOutput> splitOutputs =
                        BuildOutputs.load(VariantScope.TaskOutputType.MERGED_MANIFESTS, directory);
                if (splitOutputs.isEmpty()) {
                    throw new GradleException("Could not load manifest from " + directory);
                }

                providers.add(
                        new ConfigAction.ManifestProviderImpl(
                                splitOutputs.iterator().next().getOutputFile(),
                                getArtifactName(artifact)));
            }
        }

        return providers;
    }

    // TODO put somewhere else?
    @NonNull
    @Internal
    public static String getArtifactName(@NonNull ResolvedArtifactResult artifact) {
        ComponentIdentifier id = artifact.getId().getComponentIdentifier();
        if (id instanceof ProjectComponentIdentifier) {
            return ((ProjectComponentIdentifier) id).getProjectPath();
        } else if (id instanceof ModuleComponentIdentifier) {
            ModuleComponentIdentifier mID = (ModuleComponentIdentifier) id;
            return mID.getGroup() + ":" + mID.getModule() + ":" + mID.getVersion();
        } else if (id instanceof OpaqueComponentArtifactIdentifier) {
            // this is the case for local jars.
            // FIXME: use a non internal class.
            return id.getDisplayName();
        } else if (id instanceof ExtraComponentIdentifier) {
            return id.getDisplayName();
        } else {
            throw new RuntimeException("Unsupported type of ComponentIdentifier");
        }
    }

    @Input
    @Optional
    public String getMinSdkVersion() {
        return minSdkVersion.get();
    }

    @Input
    @Optional
    public String getTargetSdkVersion() {
        return targetSdkVersion.get();
    }

    @Input
    @Optional
    public Integer getMaxSdkVersion() {
        return maxSdkVersion.get();
    }

    /**
     * Not an input, see {@link #getOptionalFeaturesString()}.
     */
    @Internal
    public List<Feature> getOptionalFeatures() {
        return optionalFeatures;
    }

    /**
     * Synthetic input for {@link #getOptionalFeatures()}
     */
    @Input
    public List<String> getOptionalFeaturesString() {
        return optionalFeatures.stream().map(Enum::toString).collect(Collectors.toList());
    }

    @Internal
    public VariantConfiguration getVariantConfiguration() {
        return variantConfiguration;
    }

    public void setVariantConfiguration(
            VariantConfiguration<CoreBuildType, CoreProductFlavor, CoreProductFlavor> variantConfiguration) {
        this.variantConfiguration = variantConfiguration;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getManifests() {
        return manifests.getArtifactFiles();
    }

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getFeatureManifests() {
        if (featureManifests == null) {
            return null;
        }
        return featureManifests.getArtifactFiles();
    }

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getMicroApkManifest() {
        return microApkManifest;
    }

/*    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getCompatibleScreensManifest() {
        return compatibleScreensManifest;
    }*/

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getPackageManifest() {
        return packageManifest;
    }

    @Input
    @Optional
    public Set<String> getSupportedAbis() {
        return supportedAbis;
    }

    @Input
    @Optional
    public String getBuildTargetAbi() {
        return buildTargetAbi;
    }

    @Input
    @Optional
    public String getBuildTargetDensity() {
        return buildTargetDensity;
    }

    @Input
    @Optional
    public String getFeatureName() {
        return featureName;
    }

    public static class ConfigAction implements TaskConfigAction<MergeManifests> {

        protected final VariantScope variantScope;
        protected final List<Feature> optionalFeatures;
        @Nullable
        private final File reportFile;

        public ConfigAction(
                @NonNull VariantScope scope,
                @NonNull List<Feature> optionalFeatures,
                @Nullable File reportFile) {
            this.variantScope = scope;
            this.optionalFeatures = optionalFeatures;
            this.reportFile = reportFile;
        }

        @NonNull
        @Override
        public String getName() {
            return variantScope.getTaskName("process", "ShadeManifest");
        }

        @NonNull
        @Override
        public Class<MergeManifests> getType() {
            return MergeManifests.class;
        }

        @Override
        public void execute(@NonNull MergeManifests processManifestTask) {
            final BaseVariantData variantData = variantScope.getVariantData();
            final GradleVariantConfiguration config = variantData.getVariantConfiguration();
            GlobalScope globalScope = variantScope.getGlobalScope();
            AndroidBuilder androidBuilder = globalScope.getAndroidBuilder();
            ProjectOptions projectOptions = variantScope.getGlobalScope().getProjectOptions();

            processManifestTask.setAndroidBuilder(androidBuilder);
            processManifestTask.setVariantName(config.getFullName());
            processManifestTask.outputScope = variantData.getOutputScope();

            processManifestTask.setVariantConfiguration(config);

            Project project = globalScope.getProject();

//            // This includes the dependent libraries.
//            processManifestTask.manifests =
//                    variantScope.getShadeArtifactCollection(RUNTIME_CLASSPATH, ALL, MANIFEST);
            processManifestTask.manifests =
                    ShadeTaskManager.getShadeArtifactCollection(project, variantScope, MANIFEST);

            // optional manifest files too.
            if (variantScope.getMicroApkTask() != null &&
                    config.getBuildType().isEmbedMicroApp()) {
                processManifestTask.microApkManifest = project.files(
                        variantScope.getMicroApkManifestFile());
            }
//            processManifestTask.compatibleScreensManifest =
//                    variantScope.getOutput(VariantScope.TaskOutputType.COMPATIBLE_SCREEN_MANIFEST);

            processManifestTask.minSdkVersion =
                    TaskInputHelper.memoize(
                            () -> {
                                ApiVersion minSdk = config.getMergedFlavor().getMinSdkVersion();
                                return minSdk == null ? null : minSdk.getApiString();
                            });

            processManifestTask.targetSdkVersion =
                    TaskInputHelper.memoize(
                            () -> {
                                ApiVersion targetSdk =
                                        config.getMergedFlavor().getTargetSdkVersion();
                                return targetSdk == null ? null : targetSdk.getApiString();
                            });

            processManifestTask.maxSdkVersion =
                    TaskInputHelper.memoize(config.getMergedFlavor()::getMaxSdkVersion);

            processManifestTask.setManifestOutputDirectory(
                    variantScope.getManifestOutputDirectory());

            processManifestTask.setInstantRunManifestOutputDirectory(
                    variantScope.getInstantRunManifestOutputDirectory());

            processManifestTask.setReportFile(reportFile);
            processManifestTask.optionalFeatures = optionalFeatures;

            processManifestTask.supportedAbis =
                    variantData.getVariantConfiguration().getSupportedAbis();
            processManifestTask.buildTargetAbi =
                    projectOptions.get(BUILD_ONLY_TARGET_ABI)
                            || variantScope
                            .getGlobalScope()
                            .getExtension()
                            .getSplits()
                            .getAbi()
                            .isEnable()
                            ? projectOptions.get(StringOption.IDE_BUILD_TARGET_ABI)
                            : null;
            processManifestTask.buildTargetDensity =
                    projectOptions.get(StringOption.IDE_BUILD_TARGET_DENSITY);

            variantScope
                    .getVariantData()
                    .addTask(TaskContainer.TaskKind.PROCESS_MANIFEST, processManifestTask);
        }

        /**
         * Implementation of AndroidBundle that only contains a manifest.
         * <p>
         * This is used to pass to the merger manifest snippet that needs to be added during
         * merge.
         */
        public static class ManifestProviderImpl implements ManifestProvider {

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

    public static class FeatureConfigAction extends ConfigAction {

        public FeatureConfigAction(
                @NonNull VariantScope scope, @NonNull List<Feature> optionalFeatures) {
            super(scope, optionalFeatures, null);
        }

        @Override
        public void execute(@NonNull MergeManifests processManifestTask) {
            super.execute(processManifestTask);

            processManifestTask.featureName =
                    ((FeatureVariantData) variantScope.getVariantData()).getFeatureName();
            processManifestTask.packageManifest =
                    variantScope.getArtifactFileCollection(
                            COMPILE_CLASSPATH, MODULE, FEATURE_APPLICATION_ID_DECLARATION);
        }
    }

    public static class BaseFeatureConfigAction extends ConfigAction {

        public BaseFeatureConfigAction(
                @NonNull VariantScope scope, @NonNull List<Feature> optionalFeatures) {
            super(scope, optionalFeatures, null);
        }

        @Override
        public void execute(@NonNull MergeManifests processManifestTask) {
            super.execute(processManifestTask);

            processManifestTask.packageManifest =
                    variantScope.getArtifactFileCollection(
                            METADATA_VALUES, MODULE, METADATA_APP_ID_DECLARATION);

            // This includes the other features.
            processManifestTask.featureManifests =
                    variantScope.getArtifactCollection(
                            METADATA_VALUES, MODULE, METADATA_FEATURE_MANIFEST);
        }
    }
}
