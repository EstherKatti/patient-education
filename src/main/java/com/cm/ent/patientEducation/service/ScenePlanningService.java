package com.cm.ent.patientEducation.service;

import com.cm.ent.patientEducation.dto.Scene;
import com.cm.ent.patientEducation.dto.ScenePlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ScenePlanningService {
    private static final int MIN_SCENES = 4;
    private static final int MAX_SCENES = 8;

    private static final String SYSTEM_PROMPT = """
            You are a scriptwriter for short patient-education videos. Given a topic title and a
            doctor's description, produce a clear, ordered sequence of scenes that teaches the topic
            step by step.

            Rules:
            - Produce between %d and %d scenes, ordered first to last.
            - Narration: Write 1 to 3 short sentences in everyday words at roughly a 6th-grade
              reading level, using the active voice. STRICT LIMIT: Maximum 20 words per scene so it
              fits within the scene duration.
            - Duration: Estimate duration in seconds (4-10) based on a speaking rate of 2.5 words
              per second.
            - Title: a short scene title (<= ~6 words).
            - The 'visual' field describes ONLY three things: (a) the SPECIFIC action, (b) the KEY
              OBJECT named concretely and unambiguously with an identifying detail, and (c) a simple
              CAMERA FRAMING that makes the action clearest.
            - Cast: the video uses a FIXED cast. Refer to characters ONLY by these role names, and
              name who appears in each scene inside the 'visual':
                * "patient"   — the person receiving care (blue shirt),
                * "caregiver" — a friend or family member helping the patient (green shirt),
                * "doctor"    — the physician (white coat),
                * "nurse"     — the nurse (blue scrubs).
              Most scenes feature just the patient. Use the doctor or nurse only when a clinician is
              actually demonstrating or speaking. Use the caregiver only when a SECOND person is
              genuinely needed (e.g. helping the patient stand). Name each character by role in the
              visual, e.g. "the caregiver holds the patient's hand and helps them stand up, medium shot".
            - Camera Framing options: Pick ONE per action.
                * "close-up of the hands" for fine manual steps (applying toothpaste),
                * "medium shot, waist up" for whole-body actions,
                * "over-the-shoulder view" when showing what the person is looking at.
            - Prop Continuity Tracker: You must establish ONE specific identity for every key object.
              Once an object is introduced, use its EXACT, identical description in every later scene
              it appears in, no matter how many scenes have passed. Example: if scene 1 uses a "blue
              plastic tongue scraper", scene 3 MUST say "blue plastic tongue scraper" — never
              abbreviate to "scraper" or "the tool", and never change an adjective.
            - Visual Restrictions: Do NOT describe the person, their appearance, art style, lighting,
              or camera/film specs. A single fixed character, style, and lighting are applied by the
              system across every scene.
            - Medical Guidelines: This is general educational guidance only. Do NOT give specific
              drug dosages, make a diagnosis, or give personalized medical advice. Assume a clinician
              reviews everything. Be accurate, neutral, and reassuring; if a medical term is
              unavoidable, explain it in plain words.

            Leave the id and order fields empty — they are assigned by the system.
            """.formatted(MIN_SCENES, MAX_SCENES);

    private final ChatClient chat;
    public ScenePlanningService(ChatClient.Builder builder) {
        this.chat = builder.defaultSystem(SYSTEM_PROMPT).build();
    }

    public ScenePlan plan(String title, String description) {
        ScenePlan raw = chat.prompt()
                .user(u -> u.text("""
                        Title: {title}

                        Description:
                        {description}
                        """)
                        .param("title", title)
                        .param("description", description))
                .call()
                .entity(ScenePlan.class);
        return normalize(raw);
    }

    private ScenePlan normalize(ScenePlan raw) {
        if (raw == null || raw.getScenes() == null || raw.getScenes().isEmpty()) {
            throw new ScenePlanningException("The model returned no scenes. Try a more detailed description.");
        }
        List<Scene> out = new ArrayList<>(raw.getScenes().size());
        int i = 0;
        for (Scene s : raw.getScenes()) {
            out.add(new Scene(
                    "sc_" + UUID.randomUUID().toString().substring(0, 8),
                    ++i,
                    safe(s.getTitle(), "Untitled scene"),
                    safe(s.getNarration(), ""),
                    safe(s.getVisual(), ""),
                    clampDuration(s.getDurationSeconds())
            ));
        }
        return new ScenePlan(List.copyOf(out));
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v.strip();
    }
    private static int clampDuration(int seconds) {
        if (seconds < 4) return 4;
        if (seconds > 12) return 12;
        return seconds;
    }

    public static class ScenePlanningException extends RuntimeException {
        public ScenePlanningException(String message) { super(message); }
    }
}
