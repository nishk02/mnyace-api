package com.mnyace.shared.security;

import com.mnyace.shared.exception.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public abstract class BaseController {
    protected String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiException.unauthorized("Not Authenticated");
        }
        return authentication.getPrincipal().toString();
    }

    protected String getCurrentSessionId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getDetails() instanceof String sessionId ? sessionId : null;
    }
}
