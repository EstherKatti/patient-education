package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.dto.EnrichedScene;
import com.cm.ent.patientEducation.dto.Scene;
import com.cm.ent.patientEducation.dto.ScenePlan;
import com.cm.ent.patientEducation.helper.CharacterProfile;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Approved ScenePlan -> EnrichedScenes.
 *  - Images: reuse the APPROVED stills from the review gate (by token) when provided; otherwise
 *    generate a consistent set. Constant character throughout.
 *  - Narration: per scene, all from the SAME Piper voice -> constant voice. Parallel.
 *  - Duration: max(LLM estimate, real narration length) so audio is never cut off.
 */
@Service
public class SceneAssetPipeline {

    private static final int IMG_W = 1280;
    private static final int IMG_H = 720;

    private final SceneImageService images;
    private final NarrationService tts;
    private final CharacterProfile character;
    private final ImageStore imageStore;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    private final TranslationService translator;

    public SceneAssetPipeline(SceneImageService images, NarrationService tts,
                              CharacterProfile character, ImageStore imageStore,
                              TranslationService translator) {
        this.images = images;
        this.tts = tts;
        this.character = character;
        this.imageStore = imageStore;
        this.translator = translator;
    }

    public List<EnrichedScene> enrich(ScenePlan plan) {
        return enrich(plan, Map.of());
    }

    /** imageTokens: sceneId -> approved image token (from the review gate). */
    public List<EnrichedScene> enrich(ScenePlan plan, Map<String, String> imageTokens) {
        return enrich(plan, imageTokens, "en");
    }

    /** language: audio language (en/hi/ml/te/ta). Narration is translated, then synthesized. */
    public List<EnrichedScene> enrich(ScenePlan plan, Map<String, String> imageTokens, String language) {
        List<Scene> scenes = plan.getScenes();
        List<Path> stills = resolveStills(scenes, imageTokens);

        List<CompletableFuture<NarrationService.Narration>> narration = scenes.stream()
                .map(s -> CompletableFuture.supplyAsync(() -> {
                    String spoken = translator.translate(s.getNarration(), language);
                    return tts.synthesize(spoken, language);
                }, pool))
                .toList();

        List<EnrichedScene> out = new ArrayList<>(scenes.size());
        for (int i = 0; i < scenes.size(); i++) {
            Scene s = scenes.get(i);
            NarrationService.Narration n = narration.get(i).join();
            int duration = Math.max(s.getDurationSeconds(), (int) Math.ceil(n.seconds()));
            out.add(new EnrichedScene(
                    s.getId(), s.getTitle(), s.getNarration(),
                    stills.get(i).toString(), n.audioFile().toString(), duration));
        }
        return out;
    }

    private List<Path> resolveStills(List<Scene> scenes, Map<String, String> imageTokens) {
        if (imageTokens == null || imageTokens.isEmpty()) {
            List<String> prompts = scenes.stream().map(Scene::getVisual).toList();
            return new ArrayList<>(images.generateScenes(character, prompts, IMG_W, IMG_H));
        }
        List<Path> stills = new ArrayList<>(scenes.size());
        for (Scene s : scenes) {
            String token = imageTokens.get(s.getId());
            Path approved = (token != null) ? imageStore.get(token) : null;
            stills.add(approved != null ? approved
                    : images.regenerate(character, s.getVisual(), IMG_W, IMG_H));
        }
        return stills;
    }
}