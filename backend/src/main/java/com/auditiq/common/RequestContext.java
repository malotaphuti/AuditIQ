package com.auditiq.common;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Access to the current HTTP request for audit metadata (IP, user agent). */
public final class RequestContext {

    private RequestContext() {
    }

    public static Optional<HttpServletRequest> current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.of(attrs.getRequest());
        }
        return Optional.empty();
    }

    public static String clientIp() {
        return current().map(req -> {
            String forwarded = req.getHeader("X-Forwarded-For");
            String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
            return ip.length() > 45 ? ip.substring(0, 45) : ip;
        }).orElse(null);
    }

    public static String userAgent() {
        return current().map(req -> req.getHeader("User-Agent"))
                .map(ua -> ua.length() > 255 ? ua.substring(0, 255) : ua)
                .orElse(null);
    }
}
