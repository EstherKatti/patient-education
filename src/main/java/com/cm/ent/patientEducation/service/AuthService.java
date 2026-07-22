package com.cm.ent.patientEducation.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class AuthService {

    @Value("${google.client.id}")
    private String googleClientId;
    @Value("${google.allowed.emails}")
    private String allowedEmailsRaw;


    /** Verifies the idToken and returns the email if it is on the allowlist, else null. */
    public String verifiedEmail(String idToken) {
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();
            GoogleIdToken googleIdToken = verifier.verify(idToken);
            if (googleIdToken == null) return null;
            String email = googleIdToken.getPayload().getEmail();
            List<String> allowedEmails = Arrays.asList(allowedEmailsRaw.split(","));
            boolean ok = allowedEmails.stream().map(String::trim).anyMatch(e -> e.equalsIgnoreCase(email));
            return ok ? email : null;
        } catch (Exception e) {
            return null;
        }
    }

}