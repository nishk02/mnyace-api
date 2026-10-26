package com.mnyace.auth;

public record LogoutRequest(
        String refreshToken
) {
}