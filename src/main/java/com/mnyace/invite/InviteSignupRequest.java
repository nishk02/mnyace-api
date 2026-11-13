package com.mnyace.invite;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InviteSignupRequest(
        @NotBlank(message = "First name is required")
        String firstName,

        @NotBlank(message = "Last name is required")
        String lastName,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
        String password,

        @NotBlank(message = "Confirm password is required")
        String confirmPassword,

        @Size(max = 50, message = "Consent version is too long")
        @Pattern(regexp = "[A-Za-z0-9._-]*", message = "Consent version has invalid characters")
        String consentVersion,

        Boolean adultConfirmed
) {
}
