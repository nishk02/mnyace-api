package com.moneyflow.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 72, message = "New password must be 8 to 72 characters")
        String newPassword,

        @NotBlank(message = "Confirm password is required")
        String confirmNewPassword,

        String deviceLabel
) {
    /** Keeps the passwords out of logs if the request is ever printed. */
    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }
}
