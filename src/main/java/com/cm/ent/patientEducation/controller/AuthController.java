package com.cm.ent.patientEducation.controller;

import com.cm.ent.patientEducation.dto.GoogleAuthRequest;
import com.cm.ent.patientEducation.dto.GoogleAuthResponse;
import com.cm.ent.patientEducation.service.AuthService;
import com.cm.ent.patientEducation.service.PremiumService;
import com.cm.ent.patientEducation.service.SessionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/patienteducation")
public class AuthController {

    private final AuthService authService;
    private final PremiumService premiumService;
    private final SessionService sessionService;

    AuthController(AuthService authService, PremiumService premiumService, SessionService sessionService) {
        this.authService = authService;
        this.premiumService = premiumService;
        this.sessionService = sessionService;
    }

    @PostMapping("/google")
    public ResponseEntity<Object> googleLogin(@Valid @RequestBody GoogleAuthRequest request,
                                               HttpServletResponse response) {
        String email = authService.verifiedEmail(request.getIdToken());

        if (email == null) {
            return ResponseEntity.status(401).body("Unauthorized: invalid token or email not allowed");
        }
        setSessionCookie(response, sessionService.create(email));
        return ResponseEntity.ok(new GoogleAuthResponse(email, premiumService.isPremium(email)));
    }

    /** Lets the frontend ask "who am I, and am I premium?" without trusting anything client-supplied. */
    @GetMapping("/me")
    public ResponseEntity<Object> me(@CookieValue(value = SessionService.COOKIE_NAME, required = false) String sessionToken) {
        String email = sessionService.resolveEmail(sessionToken);
        if (email == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        return ResponseEntity.ok(new GoogleAuthResponse(email, premiumService.isPremium(email)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Object> logout(@CookieValue(value = SessionService.COOKIE_NAME, required = false) String sessionToken,
                                          HttpServletResponse response) {
        sessionService.invalidate(sessionToken);
        clearSessionCookie(response);
        return ResponseEntity.ok().build();
    }

    private void setSessionCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(SessionService.COOKIE_NAME, token)
                .httpOnly(true)
                .secure(false) // flip to true once served over HTTPS
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofMinutes(sessionService.ttlMinutes()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearSessionCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(SessionService.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}