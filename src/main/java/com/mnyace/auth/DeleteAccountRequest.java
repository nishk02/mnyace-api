package com.mnyace.auth;

import jakarta.validation.constraints.NotBlank;

public record DeleteAccountRequest(
        @NotBlank(message = "Password is required")
        String password
) {
}
