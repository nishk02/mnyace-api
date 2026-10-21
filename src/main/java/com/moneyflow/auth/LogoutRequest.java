package com.moneyflow.auth;

public record LogoutRequest(
        String refreshToken
) {
}