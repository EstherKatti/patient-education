package com.cm.ent.patientEducation.controller;

import com.cm.ent.patientEducation.dto.ScenePlan;
import com.cm.ent.patientEducation.dto.SceneRequest;
import com.cm.ent.patientEducation.service.ScenePlanningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/patienteducation")
public class SceneController {

    private final ScenePlanningService scenePlanningService;

    public SceneController(ScenePlanningService scenePlanningService) {
        this.scenePlanningService = scenePlanningService;
    }

    @PostMapping("/scenes")
    public ResponseEntity<Object> plan(@Valid @RequestBody SceneRequest request) {
        try {
            ScenePlan scenePlan = scenePlanningService.plan(
                    request.getTitle(),
                    request.getDescription());

            return ResponseEntity.ok(scenePlan);

        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error",
                            "Could not generate scenes right now. Please try again."
                    ));
        }
    }

}