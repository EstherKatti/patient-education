package com.cm.ent.patientEducation.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonClassDescription("One scene of a short, step-by-step patient-education video.")
public class Scene {

    @JsonPropertyDescription("Leave blank — assigned by the server.")
    String id;
    @JsonPropertyDescription("Leave 0 — assigned by the server.")
    int order;
    @JsonPropertyDescription("A short scene title for the editing card, at most ~6 words.")
    String title;
    @JsonPropertyDescription("""
            The narration script read aloud for this scene: 1-3 short, plain sentences \
            in everyday words at roughly a 6th-grade reading level, active voice.""")
    String narration;
    @JsonPropertyDescription("""
            A concrete description of the single still illustration for this scene \
            (used later for image generation). Describe what is shown, not how to draw it.""")
    String visual;
    @JsonPropertyDescription("Estimated on-screen duration in seconds, typically 4 to 10.")
    int durationSeconds;

}