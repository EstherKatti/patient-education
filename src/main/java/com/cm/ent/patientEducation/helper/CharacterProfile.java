package com.cm.ent.patientEducation.helper;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
public class CharacterProfile {
    private final String dna;
    private final String style;
    private final String negativePrompt;
    private final String referenceImage;

    public CharacterProfile(
            @Value("${character.dna}") String dna,
            @Value("${character.style}") String style,
            @Value("${character.negative:}") String negativePrompt,
            @Value("${character.reference-image:}") String referenceImage) {
        this.dna = dna;
        this.style = style;
        this.negativePrompt = negativePrompt;
        this.referenceImage = referenceImage;
    }
}