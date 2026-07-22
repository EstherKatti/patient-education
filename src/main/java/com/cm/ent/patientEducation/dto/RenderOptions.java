package com.cm.ent.patientEducation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Render-time settings, independent of which backend renders.
 */
@Getter
@AllArgsConstructor
public class RenderOptions {
    private final int fps;
    private final int width;
    private final int height;
    private final String language;

    public static RenderOptions defaults() {
        return defaults("en");
    }
    public static RenderOptions defaults(String language) {
        return new RenderOptions(30, 1280, 720,
                (language == null || language.isBlank()) ? "en" : language.toLowerCase());
    }
}