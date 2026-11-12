package com.finmates.social.config.security;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Report-only logging for the azp guard: one WARN per distinct (azp, method, endpoint) so a rollout
 * in FINMATES_SECURITY_AZP_ENFORCE=false mode shows exactly which clients would be rejected where.
 * Never receives, stores or logs a token. The endpoint is reduced to at most three path segments
 * with ids/addresses/uuids replaced, and the query string is never logged.
 */
public final class AzpReportSupport {

    private static final Logger log = LoggerFactory.getLogger(AzpReportSupport.class);
    private static final int MAX_AZP_LENGTH = 64;
    private static final int MAX_SEGMENTS = 3;
    private static final int MAX_REPORTED = 500;
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "^(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "|0[xX][0-9a-fA-F]{40}|[0-9a-fA-F]{24,})$");

    private final String service;
    private final Set<String> reported = new LinkedHashSet<>();

    public AzpReportSupport(String service) {
        this.service = sanitize(service, 64);
    }

    public void reportOnce(String azp, String method, String path) {
        String safeAzp = azp == null ? "<absent>" : sanitize(azp, MAX_AZP_LENGTH);
        String safeMethod = sanitize(method, 8);
        String endpoint = normalizePath(path);
        boolean first;
        synchronized (reported) {
            first = reported.size() < MAX_REPORTED && reported.add(safeAzp + ' ' + safeMethod + ' ' + endpoint);
        }
        if (first) {
            log.warn("azp guard (report-only): service={} endpoint={} {} azp='{}' is not in the allowlist and "
                    + "would be rejected once enforcement is on", service, safeMethod, endpoint, safeAzp);
        }
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        boolean truncated = false;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (kept == MAX_SEGMENTS) {
                truncated = true;
                break;
            }
            sb.append('/').append(ID_SEGMENT.matcher(part).matches() ? "{id}" : sanitize(part, 32));
            kept++;
        }
        if (kept == 0) {
            return "/";
        }
        return truncated ? sb.append("/...").toString() : sb.toString();
    }

    static String sanitize(String value, int max) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[^\\x20-\\x7E]", "?");
        return cleaned.length() > max ? cleaned.substring(0, max) + "..." : cleaned;
    }
}
