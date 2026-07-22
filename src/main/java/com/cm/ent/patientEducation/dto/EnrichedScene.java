package com.cm.ent.patientEducation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnrichedScene {
    private String id;
    private String title;
    private String narration;
    private String imagePath;
    private String audioPath;
    private int durationSeconds;
}