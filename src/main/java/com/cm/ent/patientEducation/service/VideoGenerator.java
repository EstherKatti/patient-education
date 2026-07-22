package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.constants.Mode;
import com.cm.ent.patientEducation.dto.EnrichedScene;
import com.cm.ent.patientEducation.dto.RenderOptions;
import com.cm.ent.patientEducation.dto.RenderResult;

import java.util.List;

public interface VideoGenerator {
    Mode mode();

    RenderResult generate(List<EnrichedScene> scenes, RenderOptions options);
}