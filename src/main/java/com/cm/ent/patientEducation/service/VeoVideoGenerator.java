package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.constants.Mode;
import com.cm.ent.patientEducation.dto.EnrichedScene;
import com.cm.ent.patientEducation.dto.RenderOptions;
import com.cm.ent.patientEducation.dto.RenderResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * PREMIUM renderer: animates each doctor-approved still with Google Veo (image-to-video on the
 * same Gemini API key), then assembles the clips with FFmpeg and muxes the existing narration
 * (Piper / Gemini TTS — so multi-language works identically on premium).
 *
 * Veo bills per second of generated video (~8s per scene clip). Generation is long-running:
 * each clip takes 1-2+ minutes; we poll the returned operation until done.
 *
 * Model is configurable (video.veo.model). Check ai.google.dev/gemini-api/docs/video for current
 * IDs — set the Lite model for the cheapest tier (~$0.05/s); Fast (~$0.15/s) is the safe default.
 */
@Service
public class VeoVideoGenerator implements VideoGenerator {

    private static final int CLIP_SECONDS = 8;
    private static final long POLL_MS = 10_000;
    private static final long TIMEOUT_MS = 8 * 60_000; // per clip

    private final RestClient http;
    private final RestClient downloadHttp;
    private final String model;
    private final String apiKey;
    private final String ffmpegBin;

    public VeoVideoGenerator(
            @Value("${video.veo.model:veo-3.1-fast-generate-preview}") String model,
            @Value("${image.gemini.api-key:${spring.ai.google.genai.api-key:}}") String apiKey,
            @Value("${ffmpeg.bin:ffmpeg}") String ffmpegBin,
            RestClient.Builder builder) {
        this.model = model;
        this.apiKey = apiKey;
        this.ffmpegBin = ffmpegBin;
        this.http = builder.baseUrl("https://generativelanguage.googleapis.com/v1beta").build();
        // The file-download endpoint returns a 302 to the actual bytes; the default JDK HTTP
        // client factory doesn't follow redirects unless told to.
        this.downloadHttp = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()))
                .build();
    }

    @Override
    public Mode mode() { return Mode.PREMIUM; }

    @Override
    public RenderResult generate(List<EnrichedScene> scenes, RenderOptions options) {
        try {
            Path work = Files.createTempDirectory("veo_");
            List<Path> sceneClips = new ArrayList<>(scenes.size());

            for (int i = 0; i < scenes.size(); i++) {
                EnrichedScene s = scenes.get(i);
                Path raw = generateClip(s, work, i);                       // Veo image-to-video
                sceneClips.add(composeScene(s, raw, work, i, options));    // fit + extend + narration
            }

            Path out = concat(sceneClips, work);
            return RenderResult.completed(Mode.PREMIUM, out.toUri().toString());
        } catch (Exception e) {
            return RenderResult.failed(Mode.PREMIUM, "Premium (Veo) rendering failed: " + e.getMessage());
        }
    }

    /** Submit one image-to-video job and poll the long-running operation until the clip is ready. */
    private Path generateClip(EnrichedScene s, Path work, int index) throws Exception {
        byte[] imageBytes = Files.readAllBytes(Path.of(s.getImagePath()));
        String mime = s.getImagePath().endsWith(".png") ? "image/png" : "image/jpeg";

        String prompt = "Gently animate this exact scene: " + s.getNarration()
                + " Keep the same character, style and framing as the input image. "
                + "Subtle natural motion only; no camera cuts; no text.";

        Map<String, Object> body = Map.of(
                "instances", List.of(Map.of(
                        "prompt", prompt,
                        "image", Map.of(
                                "bytesBase64Encoded", Base64.getEncoder().encodeToString(imageBytes),
                                "mimeType", mime))),
                "parameters", Map.of(
                        "aspectRatio", "16:9",
                        "durationSeconds", CLIP_SECONDS));
                        // generateAudio omitted: this model rejects the param outright, and Veo's
                        // audio is discarded anyway — composeScene() muxes in our own narration.

        @SuppressWarnings("unchecked")
        Map<String, Object> op = http.post()
                .uri("/models/{m}:predictLongRunning", model)
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        String opName = (String) op.get("name");
        if (opName == null) throw new IllegalStateException("Veo did not return an operation name");

        long start = System.currentTimeMillis();
        Map<String, Object> done = null;
        while (System.currentTimeMillis() - start < TIMEOUT_MS) {
            Thread.sleep(POLL_MS);
            @SuppressWarnings("unchecked")
            Map<String, Object> poll = http.get()
                    .uri("/{name}", opName)
                    .header("x-goog-api-key", apiKey)
                    .retrieve()
                    .body(Map.class);
            if (Boolean.TRUE.equals(poll.get("done"))) { done = poll; break; }
        }
        if (done == null) throw new IllegalStateException("Veo clip " + (index + 1) + " timed out");
        if (done.get("error") != null) throw new IllegalStateException("Veo error: " + done.get("error"));

        byte[] video = extractVideo(done);
        Path clip = work.resolve("veo_" + index + ".mp4");
        Files.write(clip, video);
        return clip;
    }

    /** Handles both documented response shapes: an inline base64 video or a downloadable file URI. */
    @SuppressWarnings("unchecked")
    private byte[] extractVideo(Map<String, Object> op) {
        Map<String, Object> resp = (Map<String, Object>) op.get("response");
        if (resp == null) throw new IllegalStateException("no response in Veo operation");

        Map<String, Object> gvr = (Map<String, Object>) resp.getOrDefault("generateVideoResponse", resp);
        List<Map<String, Object>> samples = (List<Map<String, Object>>) (gvr.get("generatedSamples") != null
                ? gvr.get("generatedSamples") : gvr.get("generatedVideos"));
        if (samples == null || samples.isEmpty()) throw new IllegalStateException("no video in Veo response");

        Map<String, Object> video = (Map<String, Object>) samples.get(0).get("video");
        if (video == null) throw new IllegalStateException("no video field in Veo sample");

        String b64 = (String) video.get("bytesBase64Encoded");
        if (b64 != null) return Base64.getDecoder().decode(b64);

        String uri = (String) video.get("uri");
        if (uri != null) {
            return downloadHttp.get()
                    .uri(uri)
                    .header("x-goog-api-key", apiKey)
                    .retrieve()
                    .body(byte[].class);
        }
        throw new IllegalStateException("Veo video had neither bytes nor uri");
    }

    /**
     * Normalize the Veo clip to the output size, extend it (freeze last frame) if the narration
     * runs past the clip, and mux this scene's narration audio.
     */
    private Path composeScene(EnrichedScene s, Path veoClip, Path work, int index,
                              RenderOptions options) throws Exception {
        int w = options.getWidth(), h = options.getHeight(), fps = options.getFps();
        int duration = Math.max(s.getDurationSeconds(), 1);
        Path out = work.resolve("scene_" + index + ".mp4");

        String vf = "scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease,"
                + "pad=" + w + ":" + h + ":(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=" + fps
                + ",tpad=stop_mode=clone:stop_duration=" + Math.max(0, duration - CLIP_SECONDS + 1);

        run(List.of(ffmpegBin, "-y",
                "-i", veoClip.toString(),
                "-i", s.getAudioPath(),
                "-filter_complex", "[0:v]" + vf + "[v];[1:a]apad[a]",
                "-map", "[v]", "-map", "[a]",
                "-t", String.valueOf(duration),
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                out.toString()));
        return out;
    }

    private Path concat(List<Path> clips, Path work) throws Exception {
        Path list = work.resolve("list.txt");
        StringBuilder sb = new StringBuilder();
        for (Path c : clips) sb.append("file '").append(c.toString().replace("'", "'\\''")).append("'\n");
        Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);

        Path out = Files.createTempFile("video_premium_", ".mp4");
        run(List.of(ffmpegBin, "-y", "-f", "concat", "-safe", "0",
                "-i", list.toString(), "-c", "copy", out.toString()));
        return out;
    }

    private void run(List<String> args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        StringBuilder log = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) log.append(line).append('\n');
        }
        if (p.waitFor() != 0) {
            String tail = log.substring(Math.max(0, log.length() - 800));
            throw new IllegalStateException("ffmpeg failed: " + tail);
        }
    }
}
