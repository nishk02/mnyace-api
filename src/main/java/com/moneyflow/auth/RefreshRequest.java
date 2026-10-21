package com.moneyflow.auth;

public record RefreshRequest(
        String refreshToken
) {
}