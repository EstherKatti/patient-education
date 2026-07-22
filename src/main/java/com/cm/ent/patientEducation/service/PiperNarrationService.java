package com.cm.ent.patientEducation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Narration with one fixed voice per language (constant voice per video):
 *   en -> en_US-kusal-medium, hi -> hi_IN-rohan-medium,
 *   ml -> ml_IN-meera-medium, te -> te_IN-padmavathi-medium (all Piper, local & free).
 * Tamil (ta) has NO Piper voice, so it delegates to Gemini TTS (paid, same API key).
 *
 * Download each Piper voice's .onnx + .onnx.json from huggingface.co/rhasspy/piper-voices
 * and point the tts.piper.voice.<lang> properties at the .onnx files.
 */
@Service
public class PiperNarrationService implements NarrationService {

    private final String piperBin;
    private final Map<String, String> voices;
    private final GeminiTtsService geminiTts;

    public PiperNarrationService(
            @Value("${tts.piper.bin:piper}") String piperBin,
            @Value("${tts.piper.voice.en:${tts.piper.voice:}}") String enVoice,
            @Value("${tts.piper.voice.hi:}") String hiVoice,
            @Value("${tts.piper.voice.ml:}") String mlVoice,
            @Value("${tts.piper.voice.te:}") String teVoice,
            GeminiTtsService geminiTts) {
        this.piperBin = piperBin;
        this.voices = Map.of("en", enVoice, "hi", hiVoice, "ml", mlVoice, "te", teVoice);
        this.geminiTts = geminiTts;
    }

    @Override
    public Narration synthesize(String text, String language) {
        String lang = (language == null || language.isBlank()) ? "en" : language.toLowerCase();
        if ("ta".equals(lang)) return geminiTts.synthesize(text);   // no Piper Tamil voice

        String voiceModel = voices.getOrDefault(lang, "");
        if (voiceModel.isBlank()) {
            throw new IllegalStateException("No Piper voice configured for language '" + lang
                    + "'. Set tts.piper.voice." + lang + " in application.properties.");
        }
        try {
            Path wav = Files.createTempFile("tts_", ".wav");
            Process p = new ProcessBuilder(
                    piperBin, "--model", voiceModel, "--output_file", wav.toString())
                    .redirectErrorStream(true).start();
            try (OutputStream stdin = p.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (p.waitFor() != 0) throw new IllegalStateException("Piper failed");
            return new Narration(wav, durationSeconds(wav));
        } catch (Exception e) {
            throw new RuntimeException("TTS failed: " + e.getMessage(), e);
        }
    }

    private double durationSeconds(Path wav) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wav.toFile())) {
            AudioFormat f = ais.getFormat();
            return ais.getFrameLength() / f.getFrameRate();
        }
    }
}