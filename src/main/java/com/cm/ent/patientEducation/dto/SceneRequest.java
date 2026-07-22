package com.cm.ent.patientEducation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Value;

@Value
public class SceneRequest {
    @NotBlank(message = "Please enter the title for the video")
    String title;

    @NotBlank(message = "Please enter description to summarize the video")
    String description;

}