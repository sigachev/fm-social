package com.finmates.social.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;

/**
 * The request path the way Spring MVC and the authorize rules see it.
 *
 * <p>{@code HttpServletRequest.getRequestURI()} is the RAW path: {@code /api/%69nternal/x} is not the string
 * {@code /api/internal/x} there, yet Spring MVC decodes it and dispatches to {@code /api/internal/**}. A security
 * filter that compares the raw string with {@code startsWith} is therefore bypassable. This class works on the
 * decoded segments (path parameters removed, exactly what {@code PathPattern} matches) and normalises them, so a
 * gate that uses it agrees with the router on every spelling.
 *
 * <p>Fail-closed: backslashes and encoded slashes count as separators, empty and {@code .} segments are dropped,
 * {@code ..} pops the previous segment, comparisons ignore case, and a path that cannot be parsed is treated as
 * being under every prefix. It may gate more than the router would route, never less.
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    /** The decoded, normalised path segments of {@code path}. */
    static List<String> normalisedSegments(PathContainer path) {
        List<String> out = new ArrayList<>();
        for (PathContainer.Element element : path.elements()) {
            if (!(element instanceof PathContainer.PathSegment segment)) {
                continue;
            }
            for (String part : segment.valueToMatch().replace('\\', '/').split("/", -1)) {
                if (part.isEmpty() || part.equals(".")) {
                    continue;
                }
                if (part.equals("..")) {
                    if (!out.isEmpty()) {
                        out.remove(out.size() - 1);
                    }
                    continue;
                }
                out.add(part);
            }
        }
        return out;
    }

    /** True when the request path is {@code prefixSegments} itself or anything below it, however it is spelled. */
    public static boolean isUnder(HttpServletRequest request, String... prefixSegments) {
        List<String> segments;
        try {
            String context = request.getContextPath();
            RequestPath path = RequestPath.parse(request.getRequestURI(), context == null || context.isEmpty() ? null : context);
            segments = normalisedSegments(path.pathWithinApplication());
        } catch (RuntimeException unparseable) {
            return true; // fail closed: let the gate decide, the dispatcher will reject the request itself
        }
        if (segments.size() < prefixSegments.length) {
            return false;
        }
        for (int i = 0; i < prefixSegments.length; i++) {
            if (!segments.get(i).equalsIgnoreCase(prefixSegments[i])) {
                return false;
            }
        }
        return true;
    }
}
