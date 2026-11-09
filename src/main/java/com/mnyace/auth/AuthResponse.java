package com.mnyace.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

public record AuthResponse(
        String token,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String refreshToken,
        UserSummary user,
        boolean sessionRestricted
) {
    public AuthResponse(String token, String refreshToken, UserSummary user) {
        this(token, refreshToken, user, false);
    }

    public AuthResponse withoutRefreshToken() {
        return new AuthResponse(token, null, user, sessionRestricted);
    }

    public record UserSummary(
            String id,
            String firstName,
            String lastName,
            String email,
            int onboardingStep
    ) {
        public static UserSummary from (User user) {
            return new UserSummary(
                    user.getId(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getEmail(),
                    user.getOnboardingStep()
            );
        }
    }
}