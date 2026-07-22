package com.cm.ent.patientEducation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque server-side sessions, keyed by an unguessable token handed to the
 * browser as an HttpOnly cookie. The client can never read or forge the
 * token's contents (unlike a plain "email" field in a request body), so this
 * is the source of truth for "who is making this request".
 */
@Service
public class SessionService {

    public static final String COOKIE_NAME = "pe_session";

    @Value("${session.ttl-minutes:480}")
    private long ttlMinutes;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, SessionRecord> sessions = new ConcurrentHashMap<>();

    public String create(String email) {
        String token = generateToken();
        sessions.put(token, new SessionRecord(email, Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES)));
        return token;
    }

    public String resolveEmail(String token) {
        if (token == null) return null;
        SessionRecord record = sessions.get(token);
        if (record == null) return null;
        if (Instant.now().isAfter(record.expiresAt())) {
            sessions.remove(token);
            return null;
        }
        return record.email();
    }

    public void invalidate(String token) {
        if (token != null) sessions.remove(token);
    }

    public long ttlMinutes() {
        return ttlMinutes;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record SessionRecord(String email, Instant expiresAt) {}
}