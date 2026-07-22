package com.cm.ent.patientEducation.controller;

import com.cm.ent.patientEducation.constants.Mode;
import com.cm.ent.patientEducation.dto.Scene;
import com.cm.ent.patientEducation.dto.ScenePlan;
import com.cm.ent.patientEducation.dto.VideoRequest;
import com.cm.ent.patientEducation.service.JobRegistry;
import com.cm.ent.patientEducation.service.PremiumService;
import com.cm.ent.patientEducation.service.SessionService;
import com.cm.ent.patientEducation.service.VideoJobWorker;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/patienteducation/videos")
public class VideoController {

    private final JobRegistry registry;
    private final VideoJobWorker worker;
    private final PremiumService premiumService;
    private final SessionService sessionService;

    public VideoController(JobRegistry registry, VideoJobWorker worker, PremiumService premiumService,
                            SessionService sessionService) {
        this.registry = registry;
        this.worker = worker;
        this.premiumService = premiumService;
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<Object> generateVideo(@Valid @RequestBody VideoRequest request,
                                                 @CookieValue(value = SessionService.COOKIE_NAME, required = false) String sessionToken) {
        try {
            List<Scene> scenes = request.getScenes();
            if (scenes == null || scenes.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No scenes to render."));
            }
            ScenePlan plan = new ScenePlan(scenes);
            Mode mode = (request.getMode() != null) ? request.getMode() : Mode.STANDARD;
            Map<String, String> imageTokens = (request.getImageTokens() != null)
                    ? request.getImageTokens() : Map.of();
            String language = (request.getLanguage() != null && !request.getLanguage().isBlank())
                    ? request.getLanguage() : "en";

            // Premium eligibility is decided from the server-side session, never from anything
            // the client claims in the request body — that field can't be trusted.
            String email = sessionService.resolveEmail(sessionToken);
            if (mode == Mode.PREMIUM && !premiumService.isPremium(email)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Premium account required for premium rendering."));
            }

            String jobId = registry.create();
            worker.run(jobId, plan, mode, imageTokens, language);
            return ResponseEntity.accepted().body(Map.of("jobId", jobId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not start video generation. Please try again."));
        }
    }

    @GetMapping("/{jobId}/events")
    public SseEmitter events(@PathVariable String jobId) {
        return registry.subscribe(jobId);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<Object> status(@PathVariable String jobId) {
        JobRegistry.Job job = registry.get(jobId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown job"));
        }
        return ResponseEntity.ok(Map.of(
                "status", job.getStatus().name(),
                "stage", job.getStage(),
                "percent", job.getPercent(),
                "previewUrl", job.getPreviewUrl() == null ? "" : job.getPreviewUrl(),
                "error", job.getError() == null ? "" : job.getError()));
    }

    @GetMapping("/{jobId}/file")
    public ResponseEntity<Resource> file(@PathVariable String jobId) {
        JobRegistry.Job job = registry.get(jobId);
        if (job == null || job.getVideoFile() == null || !Files.exists(job.getVideoFile())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("video/mp4"))
                .body(new FileSystemResource(job.getVideoFile()));
    }

    @PostMapping("/{jobId}/publish")
    public ResponseEntity<Object> publish(@PathVariable String jobId) {
        JobRegistry.Job job = registry.get(jobId);
        if (job == null || job.getVideoFile() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Nothing to publish."));
        }
        // TODO: upload job.getVideoFile() via the YouTube Data API (resumable), return
        // the link, then delete the temp MP4. Reuse google-api-client (already a dependency).
        return ResponseEntity.ok(Map.of("message", "Upload step not wired yet (YouTube Data API)."));
    }

}