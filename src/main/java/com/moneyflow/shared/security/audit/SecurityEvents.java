package com.moneyflow.shared.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyflow.shared.security.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes one JSON line per security event, using Elastic Common Schema field names.
 * Never pass passwords, tokens, OTP codes or email addresses here; identify people by user id only.
 */
@Component
public class SecurityEvents {
    private static final Logger LOG = LoggerFactory.getLogger("SECURITY_EVENTS");
    private static final int MAX_USER_AGENT_LENGTH = 200;

    private final ObjectMapper objectMapper;
    private final String serviceName;
    private final String environment;

    public SecurityEvents(ObjectMapper objectMapper, Environment env,
                          @Value("${spring.application.name:moneyflow-api}") String serviceName) {
        this.objectMapper = objectMapper;
        this.serviceName = serviceName;
        String[] profiles = env.getActiveProfiles();
        this.environment = profiles.length > 0 ? profiles[0] : "default";
    }

    public void record(SecurityEvent event, String userId, String reason) {
        record(event, userId, reason, Map.of());
    }

    public void record(SecurityEvent event, String userId, String reason, Map<String, String> labels) {
        try {
            LOG.info(objectMapper.writeValueAsString(document(event, userId, reason, labels)));
        } catch (Exception e) {
            // logging must never break the request it describes
            LOG.warn("{\"event\":{\"action\":\"security_log_failure\"}}");
        }
    }

    private Map<String, Object> document(SecurityEvent event, String userId, String reason, Map<String, String> labels) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("@timestamp", Instant.now().toString());

        Map<String, Object> eventFields = new LinkedHashMap<>();
        eventFields.put("category", event.category());
        eventFields.put("action", event.action());
        eventFields.put("outcome", event.outcome());
        if (reason != null) {
            eventFields.put("reason", reason);
        }
        doc.put("event", eventFields);

        if (userId != null) {
            doc.put("user", Map.of("id", userId));
        }

        HttpServletRequest request = currentRequest();
        if (request != null) {
            doc.put("source", Map.of("ip", ClientIp.of(request)));
            String userAgent = request.getHeader("User-Agent");
            if (userAgent != null) {
                doc.put("user_agent", Map.of("original", cap(userAgent)));
            }
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            doc.put("http", Map.of("request", Map.of("id", requestId)));
        }

        doc.put("service", Map.of("name", serviceName, "environment", environment));
        if (!labels.isEmpty()) {
            doc.put("labels", labels);
        }
        return doc;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    private static String cap(String value) {
        return value.length() <= MAX_USER_AGENT_LENGTH ? value : value.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
