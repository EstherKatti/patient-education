package com.cm.ent.patientEducation.helper;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter implements Filter {

    @Value("${rate.limit.base-backoff-seconds:5}")
    private int baseBackoffSeconds;

    @Value("${rate.limit.max-backoff-seconds:3600}")
    private int maxBackoffSeconds;

    private final ConcurrentHashMap<String, Integer> failureCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> blockedUntil = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();
        if (!path.contains("/login") && !path.contains("/auth/google")) {
            chain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        long now = System.currentTimeMillis();

        Long blockExpiry = blockedUntil.get(ip);
        if (blockExpiry != null && now < blockExpiry) {
            long retryAfter = (blockExpiry - now + 999) / 1000;
            sendTooManyRequests(response, retryAfter);
            return;
        }

        StatusCapturingResponse wrapped = new StatusCapturingResponse(response);
        chain.doFilter(request, wrapped);

        int status = wrapped.getStatus();
        if (status == 401) {
            int failures = failureCount.merge(ip, 1, Integer::sum);
            long backoff = Math.min((long) baseBackoffSeconds * (1L << (failures - 1)), maxBackoffSeconds);
            blockedUntil.put(ip, now + backoff * 1000);
        } else if (status >= 200 && status < 300) {
            failureCount.remove(ip);
            blockedUntil.remove(ip);
        }
    }

    private void sendTooManyRequests(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.getWriter().write(
                "{\"error\":\"Too many requests\",\"retryAfterSeconds\":" + retryAfterSeconds + "}"
        );
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static class StatusCapturingResponse extends HttpServletResponseWrapper {
        private int status = 200;

        StatusCapturingResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
            super.setStatus(sc);
        }

        @Override
        public void sendError(int sc) throws IOException {
            this.status = sc;
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            this.status = sc;
            super.sendError(sc, msg);
        }

        @Override
        public int getStatus() {
            return status;
        }
    }
}