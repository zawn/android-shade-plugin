package com.house365.build.gradle.tasks;

import com.android.build.gradle.internal.scope.VariantOutputScope;
import com.android.build.gradle.tasks.MergeManifests;
import com.android.manifmerger.ManifestMerger2;

import java.util.List;

/**
 * 覆盖默认的{@link MergeManifests.ConfigAction#getName()}方法,为Shader的MergeManifests Task 配置正确的名称.
 * <p>
 * Created by zhangzhenli on 2017/2/13.
 */
public class MergeShaderManifestsConfigAction extends MergeManifests.ConfigAction {


    private final VariantOutputScope scope;
    private final List<ManifestMerger2.Invoker.Feature> optionalFeatures;

    public MergeShaderManifestsConfigAction(VariantOutputScope scope, List<ManifestMerger2.Invoker.Feature> optionalFeatures) {
        super(scope, optionalFeatures);
        this.scope = scope;
        this.optionalFeatures = optionalFeatures;
    }

    @Override
    public String getName() {
        return scope.getTaskName("process", "ShaderManifest");
    }
}
