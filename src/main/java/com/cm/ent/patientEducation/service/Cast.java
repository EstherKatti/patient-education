package com.cm.ent.patientEducation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.core.io.Resource;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * The fixed cast for patient-education videos. Each role has a locked DNA (wardrobe/appearance)
 * and a reference image (character-sheet crop). Scenes name roles in their 'visual' text; this
 * component detects which roles appear so the image services can compose the right identities
 * (and, on Nano Banana, attach the right reference images) — including two-person scenes.
 * <p>
 * Roles: patient (blue shirt), caregiver (green shirt), doctor (white coat), nurse (blue scrubs).
 */
@Component
public class Cast {

    public record Member(String role, String dna, byte[] reference, String mime) {
    }

    // role -> keywords that indicate that role appears in a scene's visual
    private static final Map<String, String[]> KEYWORDS = new LinkedHashMap<>() {{
        put("doctor", new String[]{"doctor", "physician", "clinician", "white coat"});
        put("nurse", new String[]{"nurse", "scrubs"});
        put("caregiver", new String[]{"caregiver", "friend", "helper", "family member",
                "second person", "companion", "green shirt", "green t-shirt"});
        put("patient", new String[]{"patient", "blue shirt", "blue t-shirt", "the man",
                "a man", "the person", "a person", "someone", "individual", "man"});
    }};

    private final Map<String, Member> byRole = new LinkedHashMap<>();

    public Cast(
            @Value("${cast.patient.dna:a friendly young man in his twenties, short tousled black hair, thick dark eyebrows, warm light-tan skin, wearing a plain royal-blue crew-neck t-shirt}") String patientDna,
            @Value("${cast.patient.reference:classpath:character/cast/patient.png}") String patientRef,
            @Value("${cast.caregiver.dna:a friendly young man in his twenties, short dark curly hair, warm light-tan skin, wearing a plain olive-green crew-neck t-shirt}") String caregiverDna,
            @Value("${cast.caregiver.reference:classpath:character/cast/caregiver.png}") String caregiverRef,
            @Value("${cast.doctor.dna:a friendly woman doctor in her thirties, dark hair in a low ponytail, glasses, wearing a white lab coat over a blue collared shirt}") String doctorDna,
            @Value("${cast.doctor.reference:classpath:character/cast/doctor.png}") String doctorRef,
            @Value("${cast.nurse.dna:a friendly woman nurse in her twenties, light-brown hair tied back, wearing light-blue scrubs}") String nurseDna,
            @Value("${cast.nurse.reference:classpath:character/cast/nurse.png}") String nurseRef,
            ResourceLoader loader) {
        add("patient", patientDna, patientRef, loader);
        add("caregiver", caregiverDna, caregiverRef, loader);
        add("doctor", doctorDna, doctorRef, loader);
        add("nurse", nurseDna, nurseRef, loader);
    }

    private void add(String role, String dna, String refPath, ResourceLoader loader) {
        byte[] bytes = null;
        String mime = "image/png";
        try {
            Resource r = loader.getResource(refPath);
            if (r.exists()) {
                bytes = r.getInputStream().readAllBytes();
                if (refPath.endsWith(".jpg") || refPath.endsWith(".jpeg")) mime = "image/jpeg";
            }
        } catch (Exception ignored) {
        }
        byRole.put(role, new Member(role, dna, bytes, mime));
    }

    public Member get(String role) {
        return byRole.get(role);
    }

    public Member defaultMember() {
        return byRole.get("patient");
    }

    /**
     * Which cast members appear in this scene, in order of first mention, de-duplicated.
     * Falls back to the patient if no role is named. Capped at 3 to keep prompts sane.
     */
    public List<Member> detect(String visual) {
        String v = (visual == null) ? "" : visual.toLowerCase();
        Map<String, Integer> firstIndex = new LinkedHashMap<>();
        for (var e : KEYWORDS.entrySet()) {
            int best = Integer.MAX_VALUE;
            for (String kw : e.getValue()) {
                int i = v.indexOf(kw);
                if (i >= 0 && i < best) best = i;
            }
            if (best != Integer.MAX_VALUE) firstIndex.put(e.getKey(), best);
        }
        List<Member> members = firstIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(e -> byRole.get(e.getKey()))
                .limit(3)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        if (members.isEmpty()) members.add(defaultMember());
        return members;
    }

}