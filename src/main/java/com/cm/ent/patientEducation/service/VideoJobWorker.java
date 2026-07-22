package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.constants.Mode;
import com.cm.ent.patientEducation.dto.RenderOptions;
import com.cm.ent.patientEducation.dto.RenderResult;
import com.cm.ent.patientEducation.dto.ScenePlan;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runs one generation off the request thread, bridging progress to the JobRegistry (SSE).
 */
@Service
public class VideoJobWorker {

    private final VideoGenerationOrchestrator orchestrator;
    private final JobRegistry registry;

    public VideoJobWorker(VideoGenerationOrchestrator orchestrator, JobRegistry registry) {
        this.orchestrator = orchestrator;
        this.registry = registry;
    }

    @Async("videoExecutor")
    public void run(String jobId, ScenePlan plan, Mode mode, Map<String, String> imageTokens, String language) {
        registry.markRunning(jobId);
        try {
            RenderResult result = orchestrator.generate(
                    plan, mode, RenderOptions.defaults(language),
                    (stage, percent) -> registry.update(jobId, stage, percent),
                    imageTokens);

            if (result.getStatus() == RenderResult.Status.COMPLETED) {
                registry.complete(jobId, Path.of(URI.create(result.getOutputUri())));
            } else {
                registry.fail(jobId, result.getMessage());
            }
        } catch (Exception e) {
            registry.fail(jobId, e.getMessage());
        }
    }
}