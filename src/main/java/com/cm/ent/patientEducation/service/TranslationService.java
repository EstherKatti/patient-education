package com.cm.ent.patientEducation.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Translates narration into the video's audio language just before TTS (Gemini text model). */
@Service
public class TranslationService {

    private static final Map<String, String> NAMES = Map.of(
            "en", "English", "hi", "Hindi", "ml", "Malayalam", "te", "Telugu", "ta", "Tamil");

    private final ChatClient chat;

    public TranslationService(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    public String translate(String text, String language) {
        String lang = (language == null || language.isBlank()) ? "en" : language.toLowerCase();
        if ("en".equals(lang) || text == null || text.isBlank()) return text;
        String name = NAMES.getOrDefault(lang, lang);
        return chat.prompt()
                .system("You translate patient-education narration. Translate the user's text into "
                        + name + " at the same simple, everyday reading level. Keep it natural for "
                        + "spoken narration. Return ONLY the translation, nothing else.")
                .user(text)
                .call()
                .content()
                .trim();
    }
}