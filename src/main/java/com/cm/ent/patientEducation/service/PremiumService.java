package com.cm.ent.patientEducation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/** Premium privilege check: emails listed in premium.emails get the PREMIUM workflow. */
@Service
public class PremiumService {

    private final List<String> premiumEmails;

    public PremiumService(@Value("${premium.emails:}") String premiumEmailsRaw) {
        this.premiumEmails = Arrays.stream(premiumEmailsRaw.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    public boolean isPremium(String email) {
        return email != null && premiumEmails.stream().anyMatch(e -> e.equalsIgnoreCase(email));
    }
}
