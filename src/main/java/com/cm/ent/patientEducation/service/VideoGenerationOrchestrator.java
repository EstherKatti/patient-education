package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.constants.Mode;
import com.cm.ent.patientEducation.dto.EnrichedScene;
import com.cm.ent.patientEducation.dto.RenderOptions;
import com.cm.ent.patientEducation.dto.RenderResult;
import com.cm.ent.patientEducation.dto.ScenePlan;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Post-approval flow: enrich (reusing approved images) -> render -> delete local temp assets. */
@Service
public class VideoGenerationOrchestrator {

    @FunctionalInterface
    public interface ProgressListener {
        void update(String stage, int percent);
        ProgressListener NOOP = (s, p) -> { };
    }

    private final SceneAssetPipeline assets;
    private final VideoGeneratorRegistry registry;
    private final ImageStore imageStore;

    public VideoGenerationOrchestrator(SceneAssetPipeline assets, VideoGeneratorRegistry registry,
                                       ImageStore imageStore) {
        this.assets = assets;
        this.registry = registry;
        this.imageStore = imageStore;
    }

    public RenderResult generate(ScenePlan plan, Mode mode, RenderOptions options, ProgressListener progress) {
        return generate(plan, mode, options, progress, Map.of());
    }

    public RenderResult generate(ScenePlan plan, Mode mode, RenderOptions options,
                                 ProgressListener progress, Map<String, String> imageTokens) {
        progress.update("Generating narration", 15);
        List<EnrichedScene> scenes = assets.enrich(plan, imageTokens, options.getLanguage());

        progress.update("Rendering video", 55);
        RenderResult result;
        try {
            result = registry.forMode(mode).generate(scenes, options);
        } finally {
            cleanup(scenes);
            if (imageTokens != null) imageTokens.values().forEach(imageStore::evict);
        }
        progress.update("Done", 100);
        return result;
    }

    private void cleanup(List<EnrichedScene> scenes) {
        for (EnrichedScene s : scenes) {
            delete(s.getImagePath());
            delete(s.getAudioPath());
        }
    }

    private void delete(String path) {
        try { Files.deleteIfExists(Path.of(path)); } catch (Exception ignored) { }
    }
}
