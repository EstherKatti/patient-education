package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.helper.CharacterProfile;

import java.nio.file.Path;
import java.util.List;

public interface SceneImageService {
    String provider();
    List<Path> generateScenes(CharacterProfile character, List<String> scenePrompts, int width, int height);
    /** Re-roll a single scene's image (fresh variation) for the review gate. */
    Path regenerate(CharacterProfile character, String scenePrompt, int width, int height);
}