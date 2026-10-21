package com.moneyflow.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

public record AuthResponse(
        String token,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String refreshToken,
        UserSummary user
) {
    public AuthResponse withoutRefreshToken() {
        return new AuthResponse(token, null, user);
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