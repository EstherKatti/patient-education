package com.cm.ent.patientEducation.service;

import java.nio.file.Path;

public interface NarrationService {
    /** Synthesize narration in the given language code: en, hi, ml, te, ta. */
    Narration synthesize(String text, String language);
    default Narration synthesize(String text) { return synthesize(text, "en"); }
    record Narration(Path audioFile, double seconds) {}
}