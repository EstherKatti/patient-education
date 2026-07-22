package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.constants.Mode;
import com.cm.ent.patientEducation.dto.EnrichedScene;
import com.cm.ent.patientEducation.dto.RenderOptions;
import com.cm.ent.patientEducation.dto.RenderResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * STANDARD path. Renders in-process with FFmpeg — no Node, no hops. Per scene: still scaled to
 * fit 16:9 WITHOUT cropping (letterbox-pad if needed, so the subject is never pushed off-frame),
 * a gentle centered Ken Burns, a burned caption, and the narration. Clips are concatenated.
 *
 * Voice is constant (one Piper voice). A real font is auto-detected so captions actually render.
 */
@Component
public class FfmpegVideoGenerator implements VideoGenerator {

    private static final String[] FONT_CANDIDATES = {
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf"
    };

    private final String ffmpegBin;
    private final String fontFile;
    private final boolean canDrawText;
    private final String iconDir;
    private final boolean kenBurns;
    private final String padColor;
    private final String fillMode;
    private final String captionLanguages;

    public FfmpegVideoGenerator(@Value("${ffmpeg.bin:ffmpeg}") String ffmpegBin,
                                @Value("${ffmpeg.font:}") String fontFile,
                                @Value("${ffmpeg.icon-dir:}") String iconDir,
                                @Value("${ffmpeg.kenburns:true}") boolean kenBurns,
                                @Value("${ffmpeg.pad-color:auto}") String padColor,
                                @Value("${ffmpeg.fill-mode:fit}") String fillMode,
                                @Value("${ffmpeg.caption-languages:en}") String captionLanguages) {
        this.ffmpegBin = ffmpegBin;
        this.fontFile = resolveFont(fontFile);
        this.canDrawText = hasDrawText(ffmpegBin);
        this.iconDir = iconDir;
        this.kenBurns = kenBurns;
        this.padColor = padColor;
        this.fillMode = fillMode;
        this.captionLanguages = captionLanguages;
    }

    /** Optional per-step icon: ${ffmpeg.icon-dir}/step{N}.png, overlaid top-left if present. */
    private Path resolveIcon(int step) {
        if (iconDir == null || iconDir.isBlank()) return null;
        Path p = Paths.get(iconDir, "step" + step + ".png");
        return Files.exists(p) ? p : null;
    }

    /** Some FFmpeg builds (e.g. macOS without libfreetype) lack drawtext; probe once at startup. */
    private static boolean hasDrawText(String bin) {
        try {
            Process p = new ProcessBuilder(bin, "-hide_banner", "-filters")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains(" drawtext ")) { p.waitFor(); return true; }
                }
            }
            p.waitFor();
        } catch (Exception ignored) { }
        return false;
    }

    private static String resolveFont(String configured) {
        if (configured != null && !configured.isBlank() && Files.exists(Paths.get(configured))) return configured;
        for (String c : FONT_CANDIDATES) if (Files.exists(Paths.get(c))) return c;
        return "";
    }

    @Override
    public Mode mode() { return Mode.STANDARD; }

    @Override
    public RenderResult generate(List<EnrichedScene> scenes, RenderOptions options) {
        Path work = null;
        try {
            work = Files.createTempDirectory("render_");
            int w = options.getWidth(), h = options.getHeight(), fps = options.getFps();

            List<Path> clips = new ArrayList<>();
            for (int i = 0; i < scenes.size(); i++) {
                clips.add(buildClip(scenes.get(i), i, work, w, h, fps, options));
            }

            StringBuilder list = new StringBuilder();
            for (Path c : clips) list.append("file '").append(c.toAbsolutePath()).append("'\n");
            Path listFile = work.resolve("concat.txt");
            Files.writeString(listFile, list.toString(), StandardCharsets.UTF_8);

            Path out = Files.createTempFile("video_", ".mp4");
            run(List.of(ffmpegBin, "-y", "-f", "concat", "-safe", "0",
                    "-i", listFile.toString(), "-c", "copy", out.toString()));

            return RenderResult.completed(mode(), out.toUri().toString());
        } catch (Exception e) {
            return RenderResult.failed(mode(), "FFmpeg render failed: " + e.getMessage());
        } finally {
            if (work != null) deleteQuietly(work);
        }
    }

    private Path buildClip(EnrichedScene s, int i, Path work, int w, int h, int fps, RenderOptions options) throws Exception {
        Path clip = work.resolve("clip" + i + ".mp4");
        int dur = Math.max(1, s.getDurationSeconds());
        int frames = dur * fps;
        int step = i + 1;

        Path icon = resolveIcon(step);
        boolean hasIcon = icon != null;
        // Indic scripts need a font with those glyphs + harfbuzz shaping; DejaVu has neither,
        // so burned text is enabled only for languages listed in ffmpeg.caption-languages.
        boolean langOk = ("," + captionLanguages.toLowerCase().replace(" ", "") + ",")
                .contains("," + options.getLanguage() + ",");
        boolean text = canDrawText && !fontFile.isBlank() && langOk;

        StringBuilder vf = new StringBuilder();
        if ("cover".equalsIgnoreCase(fillMode)) {
            // Edge-to-edge: scale up and crop to fill the whole frame. The crop is biased toward
            // the TOP of the image (faces live there) so heads survive; the loss comes from the
            // lower torso instead. Note: a square image loses ~44% of its height this way — the
            // clean solution is native 16:9 generation (image.gemini.aspect-ratio=16:9).
            vf.append("[0:v]scale=").append(w).append(":").append(h)
                    .append(":force_original_aspect_ratio=increase,")
                    .append("crop=").append(w).append(":").append(h)
                    .append(":(iw-ow)/2:(ih-oh)*0.18,setsar=1");
        } else {
            // fit (default): show the WHOLE image, fill the sides. 'auto' samples the image's own
            // edge color so the fill blends in; otherwise use the configured color.
            String fill = "auto".equalsIgnoreCase(padColor) ? sampleEdgeColor(s.getImagePath()) : padColor;
            vf.append("[0:v]scale=").append(w).append(":").append(h)
                    .append(":force_original_aspect_ratio=decrease,")
                    .append("pad=").append(w).append(":").append(h)
                    .append(":(ow-iw)/2:(oh-ih)/2:color=").append(fill)
                    .append(",setsar=1");
        }

        if (kenBurns) {
            // Upscale 2x BEFORE zoompan so per-frame pixel rounding is sub-pixel at output
            // resolution — this is what removes the "shaky"/jittery Ken Burns wobble.
            vf.append(",scale=").append(w * 2).append(":").append(h * 2).append(":flags=bicubic,")
                    .append("zoompan=z='min(zoom+0.0006,1.10)':d=").append(frames)
                    .append(":x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=").append(w).append("x").append(h)
                    .append(":fps=").append(fps);
        } else {
            // Rock-steady: no motion at all.
            vf.append(",fps=").append(fps);
        }

        if (text) {
            // Step number badge, top-left (skipped if an icon fills that slot).
            if (!hasIcon) {
                int badge = 66, bx = 40, by = 36;
                vf.append(",drawbox=x=").append(bx).append(":y=").append(by)
                        .append(":w=").append(badge).append(":h=").append(badge)
                        .append(":color=0x14233A@0.92:t=fill")
                        .append(",drawtext=fontfile=").append(esc(fontFile))
                        .append(":text='").append(step).append("'")
                        .append(":fontcolor=white:fontsize=40")
                        .append(":x=").append(bx).append("+(").append(badge).append("-text_w)/2")
                        .append(":y=").append(by).append("+(").append(badge).append("-text_h)/2");
            }
            // Step title pill, top — to the right of the badge/icon.
            if (s.getTitle() != null && !s.getTitle().isBlank()) {
                Path titleFile = work.resolve("title" + i + ".txt");
                Files.writeString(titleFile, s.getTitle().trim(), StandardCharsets.UTF_8);
                vf.append(",drawtext=fontfile=").append(esc(fontFile))
                        .append(":textfile=").append(esc(titleFile.toString()))
                        .append(":fontcolor=white:fontsize=34")
                        .append(":box=1:boxcolor=0x14233A@0.85:boxborderw=14")
                        .append(":x=120:y=46");
            }
            // Narration caption, bottom — the spoken words (accessibility + reinforcement).
            if (s.getNarration() != null && !s.getNarration().isBlank()) {
                Path capFile = work.resolve("cap" + i + ".txt");
                Files.writeString(capFile, wrap(s.getNarration(), 50), StandardCharsets.UTF_8);
                vf.append(",drawtext=fontfile=").append(esc(fontFile))
                        .append(":textfile=").append(esc(capFile.toString()))
                        .append(":fontcolor=white:fontsize=36:line_spacing=8")
                        .append(":box=1:boxcolor=black@0.6:boxborderw=20")
                        .append(":x=(w-text_w)/2:y=h-text_h-56");
            }
        }

        if (hasIcon) {
            vf.append("[base];[2:v]scale=72:72[ic];[base][ic]overlay=40:34[v]");
        } else {
            vf.append("[v]");
        }
        vf.append(";[1:a]apad[a]");

        List<String> args = new ArrayList<>(List.of(ffmpegBin, "-y",
                "-loop", "1", "-i", s.getImagePath(),
                "-i", s.getAudioPath()));
        if (hasIcon) { args.add("-loop"); args.add("1"); args.add("-i"); args.add(icon.toString()); }
        args.add("-filter_complex"); args.add(vf.toString());
        args.add("-map"); args.add("[v]"); args.add("-map"); args.add("[a]");
        args.add("-t"); args.add(String.valueOf(dur));
        args.add("-r"); args.add(String.valueOf(fps));
        args.add("-c:v"); args.add("libx264"); args.add("-pix_fmt"); args.add("yuv420p");
        args.add("-c:a"); args.add("aac");
        args.add(clip.toString());
        run(args);
        return clip;
    }


    /**
     * Average color of the image's left and right edge columns — used by ffmpeg.pad-color=auto so
     * the side fill matches the image's own background and the frame looks seamless.
     */
    private String sampleEdgeColor(String imagePath) {
        try {
            BufferedImage img = ImageIO.read(new File(imagePath));
            if (img == null) return "0xDDE3EA";
            int w = img.getWidth(), h = img.getHeight();
            int band = Math.max(1, w / 50);           // ~2% edge band each side
            long r = 0, g = 0, b = 0, n = 0;
            int step = Math.max(1, h / 100);          // sample ~100 rows
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < band; x++) {
                    int p1 = img.getRGB(x, y), p2 = img.getRGB(w - 1 - x, y);
                    r += ((p1 >> 16) & 0xFF) + ((p2 >> 16) & 0xFF);
                    g += ((p1 >> 8) & 0xFF) + ((p2 >> 8) & 0xFF);
                    b += (p1 & 0xFF) + (p2 & 0xFF);
                    n += 2;
                }
            }
            if (n == 0) return "0xDDE3EA";
            return String.format("0x%02X%02X%02X", (int) (r / n), (int) (g / n), (int) (b / n));
        } catch (Exception e) {
            return "0xDDE3EA";
        }
    }

    /** Word-wrap narration into lines of at most `width` chars for the caption. */
    private String wrap(String text, int width) {
        StringBuilder sb = new StringBuilder();
        int line = 0;
        for (String word : text.trim().split("\\s+")) {
            if (line > 0 && line + word.length() + 1 > width) { sb.append('\n'); line = 0; }
            else if (line > 0) { sb.append(' '); line++; }
            sb.append(word); line += word.length();
        }
        return sb.toString();
    }

    private void run(List<String> args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        StringBuilder log = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (log.length() < 4000) log.append(line).append('\n');
        }
        if (p.waitFor() != 0) {
            String s = log.toString();
            throw new IllegalStateException(s.length() > 500 ? s.substring(s.length() - 500) : s);
        }
    }

    private String esc(String path) { return path.replace("\\", "/").replace(":", "\\:"); }

    private void deleteQuietly(Path dir) {
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}