package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.constants.Mode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class VideoGeneratorRegistry {
    private final Map<Mode, VideoGenerator> byMode;

    public VideoGeneratorRegistry(List<VideoGenerator> generators) {
        this.byMode = generators.stream()
                .collect(Collectors.toUnmodifiableMap(VideoGenerator::mode, Function.identity()));
    }

    public VideoGenerator forMode(Mode mode) {
        VideoGenerator g = byMode.get(mode);
        if (g == null) throw new IllegalArgumentException("No VideoGenerator for mode " + mode);
        return g;
    }
}