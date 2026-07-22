package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.constants.JobStatus;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JobRegistry {

    @Data
    public static class Job {
        final String id;
        volatile JobStatus status = JobStatus.PENDING;
        volatile String stage = "";
        volatile int percent = 0;
        volatile String previewUrl;
        volatile String error;
        volatile Path videoFile;
        volatile SseEmitter sseEmitter;

        Job(String id) {
            this.id = id;
        }
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public String create() {
        String id = UUID.randomUUID().toString().replace("-", "");
        jobs.put(id, new Job(id));
        return id;
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    public void markRunning(String id) {
        Job j = jobs.get(id);
        if (j != null) j.status = JobStatus.RUNNING;
    }

    public synchronized void update(String id, String stage, int percent) {
        Job j = jobs.get(id);
        if (j == null) return;
        j.status = JobStatus.RUNNING;
        j.stage = stage;
        j.percent = percent;
    }

    public synchronized void complete(String id, Path videoFile) {
        Job j = jobs.get(id);
        if (j == null) return;
        j.videoFile = videoFile;
        j.previewUrl = "/patienteducation/videos/" + id + "/file";
        j.status = JobStatus.COMPLETED;
        send(j, "done", Map.of("previewUrl", j.previewUrl));
        completeEmitter(j);
    }


    public synchronized void fail(String id, String message) {
        Job j = jobs.get(id);
        if (j == null) return;
        j.error = (message != null) ? message : "Generation failed";
        j.status = JobStatus.FAILED;
        send(j, "error", Map.of("message", j.error));
        completeEmitter(j);
    }

    public synchronized SseEmitter subscribe(String id) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        Job j = jobs.get(id);
        if (j == null) {
            trySend(emitter, "error", Map.of("message", "Unknown job"));
            emitter.complete();
            return emitter;
        }
        j.sseEmitter = emitter;
        emitter.onCompletion(() -> j.sseEmitter = null);
        emitter.onTimeout(() -> j.sseEmitter = null);
        switch (j.status) {
            case COMPLETED -> {
                trySend(emitter, "done", Map.of("previewUrl", j.previewUrl));
                emitter.complete();
            }
            case FAILED -> {
                trySend(emitter, "error", Map.of("message", j.error));
                emitter.complete();
            }
            default -> trySend(emitter, "progress", Map.of("stage", j.stage, "percent", j.percent));
        }
        return emitter;
    }

    private void send(Job j, String name, Object data) {
        if (j.sseEmitter != null) trySend(j.sseEmitter, name, data);
    }

    private void trySend(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            //exception
        }
    }

    private void completeEmitter(Job j) {
        if (j.sseEmitter != null) {
            try {
                j.sseEmitter.complete();
            } catch (Exception ignored) {
            }
        }
    }
}