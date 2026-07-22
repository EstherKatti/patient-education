package com.cm.ent.patientEducation.service;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds generated scene images (temp files) for the review gate, keyed by a token, so the doctor
 * can preview/regenerate before rendering and the render can reuse the APPROVED images. No DB.
 */
@Component
public class ImageStore {

    private final Map<String, Path> files = new ConcurrentHashMap<>();

    public String put(Path file) {
        String token = UUID.randomUUID().toString().replace("-", "");
        files.put(token, file);
        return token;
    }

    public Path get(String token) {
        return files.get(token);
    }

    public void evict(String token) {
        Path p = files.remove(token);
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        }

    }
}