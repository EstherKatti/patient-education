package com.cm.ent.patientEducation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Gemini TTS (gemini-2.5-flash-preview-tts) — used for Tamil, which Piper cannot speak.
 * The model auto-detects the text's language; one fixed prebuilt voice keeps narration
 * constant across scenes. Returns 24 kHz mono PCM which we wrap into a WAV.
 * Paid per use on the same billed Gemini key as image generation.
 */
@Service
public class GeminiTtsService {

    private static final int SAMPLE_RATE = 24000;

    private final RestClient http;
    private final String model;
    private final String apiKey;
    private final String voiceName;

    public GeminiTtsService(
            @Value("${tts.gemini.model:gemini-2.5-flash-preview-tts}") String model,
            @Value("${tts.gemini.voice:Kore}") String voiceName,
            @Value("${image.gemini.api-key:${spring.ai.google.genai.api-key:}}") String apiKey,
            RestClient.Builder builder) {
        this.model = model;
        this.voiceName = voiceName;
        this.apiKey = apiKey;
        this.http = builder.baseUrl("https://generativelanguage.googleapis.com/v1beta").build();
    }

    public NarrationService.Narration synthesize(String text) {
        try {
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("role", "user",
                            "parts", List.of(Map.of("text", text)))),
                    "generationConfig", Map.of(
                            "responseModalities", List.of("AUDIO"),
                            "speechConfig", Map.of("voiceConfig",
                                    Map.of("prebuiltVoiceConfig", Map.of("voiceName", voiceName)))));

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = http.post()
                    .uri("/models/{m}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            byte[] pcm = Base64.getDecoder().decode(extractAudio(resp));
            Path wav = Files.createTempFile("tts_", ".wav");
            Files.write(wav, pcmToWav(pcm, SAMPLE_RATE));
            double seconds = pcm.length / (double) (SAMPLE_RATE * 2); // 16-bit mono
            return new NarrationService.Narration(wav, seconds);
        } catch (Exception e) {
            throw new RuntimeException("Gemini TTS failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractAudio(Map<String, Object> resp) {
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
        if (candidates == null || candidates.isEmpty()) throw new IllegalStateException("no candidates");
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        for (Map<String, Object> part : (List<Map<String, Object>>) content.get("parts")) {
            Object inline = part.get("inlineData");
            if (inline == null) inline = part.get("inline_data");
            if (inline instanceof Map<?, ?> m && m.get("data") != null) return (String) m.get("data");
        }
        throw new IllegalStateException("no audio in Gemini response");
    }

    /** Minimal RIFF/WAV wrapper for 16-bit mono PCM. */
    private byte[] pcmToWav(byte[] pcm, int sampleRate) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int byteRate = sampleRate * 2;
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(byteRate)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        out.write(h.array());
        out.write(pcm);
        return out.toByteArray();
    }
}