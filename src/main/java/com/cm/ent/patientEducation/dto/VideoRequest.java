package com.cm.ent.patientEducation.dto;

import com.cm.ent.patientEducation.constants.Mode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Video generation request.
 * <p>
 * MERGE NOTE: this mirrors your existing VideoRequest and ADDS one field — imageTokens —
 * carrying the review gate's APPROVED image per scene (sceneId -> token). When present, the
 * render reuses those exact stills instead of regenerating. Keep your existing fields/annotations;
 * you only need to add imageTokens (+ its getter/setter, which Lombok's @Data provides).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoRequest {
    private String title;
    private List<Scene> scenes;
    private Mode mode;
    private Map<String, String> imageTokens;
    private String language;
}