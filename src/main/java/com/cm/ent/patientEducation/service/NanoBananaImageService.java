package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.helper.CharacterProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Paid/better image provider — Gemini "Nano Banana" (gemini-2.5-flash-image) or Nano Banana Pro.
 * Sends the uploaded character reference on every scene for real consistency.
 *
 * Hardened against quota blips: a 429 (or transient 5xx) is retried with backoff, honoring the
 * retryDelay the API returns, so one rate-limit hit doesn't kill the whole render. Only a
 * genuinely exhausted quota (after several attempts) fails the job.
 */
@Service
public class NanoBananaImageService implements SceneImageService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MS = 2000;
    private static final long MAX_BACKOFF_MS = 30000;
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"");
    private static final Pattern RETRY_IN = Pattern.compile("retry in (\\d+(?:\\.\\d+)?)s");

    private final RestClient http;
    private final String model;
    private final String apiKey;
    private final Cast cast;
    private final String aspectRatio;

    public NanoBananaImageService(
            @Value("${image.gemini.model:gemini-2.5-flash-image}") String model,
            @Value("${image.gemini.api-key:${spring.ai.google.genai.api-key:}}") String apiKey,
            @Value("${image.gemini.aspect-ratio:}") String aspectRatio,
            Cast cast,
            RestClient.Builder builder) {
        this.model = model;
        this.apiKey = apiKey;
        this.aspectRatio = aspectRatio;
        this.cast = cast;
        this.http = builder.baseUrl("https://generativelanguage.googleapis.com/v1beta").build();
    }

    @Override
    public String provider() { return "gemini:" + model; }

    @Override
    public List<Path> generateScenes(CharacterProfile c, List<String> prompts, int width, int height) {
        List<Path> out = new ArrayList<>(prompts.size());
        for (String scene : prompts) out.add(generateOne(c, scene));
        return out;
    }

    @Override
    public Path regenerate(CharacterProfile c, String scene, int width, int height) {
        return generateOne(c, scene + " (alternate take " + ThreadLocalRandom.current().nextInt(1, 9999) + ")");
    }

    private Path generateOne(CharacterProfile c, String scene) {
        List<Cast.Member> members = cast.detect(scene);

        List<Map<String, Object>> parts = new ArrayList<>();
        StringBuilder identity = new StringBuilder();
        int i = 1;
        for (Cast.Member m : members) {
            if (m.reference() != null) {
                parts.add(Map.of("inlineData", Map.of(
                        "mimeType", m.mime(),
                        "data", Base64.getEncoder().encodeToString(m.reference()))));
                identity.append("Person ").append(i).append(" is the ").append(m.role())
                        .append(" (").append(m.dna()).append("), matching reference image ")
                        .append(i).append(" for identity ONLY — same face, hair and clothing. ");
            } else {
                identity.append("Person ").append(i).append(" is the ").append(m.role())
                        .append(" (").append(m.dna()).append("). ");
            }
            i++;
        }
        int n = members.size();
        String count = (n == 1) ? "exactly one person" : "exactly " + n + " people";

        String text = String.join(" ",
                "Create ONE single illustration for a step in a patient-education video.",
                identity.toString().trim(),
                "Do NOT copy any reference's background, layout, panels, numbers, icons, title or UI.",
                "Scene to depict:", scene + ".",
                "The scene text above includes the camera framing — follow that framing exactly.",
                "Composition:", count + " — each person appears exactly ONCE. Do NOT mirror, duplicate,",
                "or split any person; no two copies of the same person; one single panel (no split",
                "screen, no grid, no collage); if the frame is wide, leave plain empty background on",
                "the sides rather than repeating the person.",
                "each person and the key object must be clearly visible and instantly recognizable; 16:9.",
                "Style:", c.getStyle() + ".",
                "Do NOT render any text, captions, numbers, icons, borders, watermarks or UI anywhere.");
        if (c.getNegativePrompt() != null && !c.getNegativePrompt().isBlank()) {
            text += " Avoid: " + c.getNegativePrompt() + ".";
        }
        parts.add(Map.of("text", text));

        Map<String, Object> genConfig = (aspectRatio == null || aspectRatio.isBlank())
                ? Map.of("responseModalities", List.of("IMAGE"))
                : Map.of("responseModalities", List.of("IMAGE"),
                "imageConfig", Map.of("aspectRatio", aspectRatio));
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", parts)),
                "generationConfig", genConfig);

        try {
            Map<String, Object> resp = postWithRetry(body);
            byte[] img = Base64.getDecoder().decode(extractImage(resp));
            Path f = Files.createTempFile("img_", ".png");
            Files.write(f, img);
            return f;
        } catch (Exception e) {
            throw new RuntimeException("Gemini image generation failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postWithRetry(Map<String, Object> body) {
        long backoff = BASE_BACKOFF_MS;
        for (int attempt = 1; ; attempt++) {
            try {
                return http.post()
                        .uri("/models/{m}:generateContent", model)
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= MAX_ATTEMPTS) throw e;
                long wait = retryDelayMs(e.getResponseBodyAsString());
                sleep(wait > 0 ? wait : backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } catch (HttpServerErrorException e) {
                if (attempt >= MAX_ATTEMPTS) throw e;
                sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    private long retryDelayMs(String responseBody) {
        if (responseBody == null) return 0;
        Matcher m = RETRY_DELAY.matcher(responseBody);
        if (m.find()) return toMs(m.group(1));
        m = RETRY_IN.matcher(responseBody);
        if (m.find()) return toMs(m.group(1));
        return 0;
    }

    private long toMs(String seconds) {
        try { return (long) (Double.parseDouble(seconds) * 1000) + 500; }
        catch (Exception e) { return 0; }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException("Interrupted while waiting on rate limit"); }
    }

    @SuppressWarnings("unchecked")
    private String extractImage(Map<String, Object> resp) {
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
        if (candidates == null || candidates.isEmpty()) throw new IllegalStateException("no candidates");
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        for (Map<String, Object> part : parts) {
            Object inline = part.get("inlineData");
            if (inline == null) inline = part.get("inline_data");
            if (inline instanceof Map<?, ?> mm && mm.get("data") != null) return (String) mm.get("data");
        }
        throw new IllegalStateException("no image in Gemini response");
    }
}