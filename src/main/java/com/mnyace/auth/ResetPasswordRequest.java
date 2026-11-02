package com.mnyace.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email,

        @NotBlank(message = "Code is required")
        @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
        String code,

        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 72, message = "New password must be 8 to 72 characters")
        String newPassword,

        @NotBlank(message = "Confirm password is required")
        String confirmNewPassword
) {
    /** Keeps the code and passwords out of logs if the request is ever printed. */
    @Override
    public String toString() {
        return "ResetPasswordRequest[redacted]";
    }
}
