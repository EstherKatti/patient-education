package com.cm.ent.patientEducation.controller;

import com.cm.ent.patientEducation.dto.Scene;
import com.cm.ent.patientEducation.dto.VideoRequest;
import com.cm.ent.patientEducation.helper.CharacterProfile;
import com.cm.ent.patientEducation.service.ImageStore;
import com.cm.ent.patientEducation.service.SceneImageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The image-review gate: generate a still per scene, regenerate one, and serve them for preview.
 */
@RestController
@RequestMapping("/patienteducation/images")
public class ImageController {
    private static final int W = 1280, H = 720;
    private final SceneImageService images;
    private final CharacterProfile character;
    private final ImageStore store;

    public ImageController(SceneImageService images, CharacterProfile character, ImageStore store) {
        this.images = images;
        this.character = character;
        this.store = store;
    }

    /**
     * Generate one image per scene; returns [{id, imageToken, imageUrl}] aligned to the scenes.
     */
    @PostMapping
    public ResponseEntity<Object> generate(@RequestBody VideoRequest request) {
        try {
            List<Scene> scenes = request.getScenes();
            if (scenes == null || scenes.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No scenes to illustrate."));
            }
            List<String> prompts = scenes.stream().map(Scene::getVisual).toList();
            List<Path> stills = images.generateScenes(character, prompts, W, H);

            List<Map<String, String>> out = new ArrayList<>(scenes.size());
            for (int i = 0; i < scenes.size(); i++) {
                String token = store.put(stills.get(i));
                out.add(Map.of("id", scenes.get(i).getId(),
                        "imageToken", token,
                        "imageUrl", "/patienteducation/images/" + token));
            }
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not generate images right now. Please try again."));
        }
    }

    /**
     * Re-roll one scene's image (after editing its text).
     */
    @PostMapping("/regenerate")
    public ResponseEntity<Object> regenerate(@RequestBody Scene scene) {
        try {
            Path img = images.regenerate(character, scene.getVisual(), W, H);
            String token = store.put(img);
            return ResponseEntity.ok(Map.of("id", scene.getId(),
                    "imageToken", token,
                    "imageUrl", "/patienteducation/images/" + token));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not regenerate that image. Please try again."));
        }
    }

    @GetMapping("/{token}")
    public ResponseEntity<Resource> serve(@PathVariable String token) throws Exception {
        Path f = store.get(token);
        if (f == null || !Files.exists(f)) return ResponseEntity.notFound().build();
        String ct = Files.probeContentType(f);
        MediaType type = (ct != null) ? MediaType.parseMediaType(ct) : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(f));
    }
}