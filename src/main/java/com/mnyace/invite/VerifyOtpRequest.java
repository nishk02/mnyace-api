package com.mnyace.invite;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
        @NotBlank(message = "OTP code is required")
        @Pattern(regexp = "\\d{6}", message = "OTP code must be 6 digits")
        String code,

        @Size(max = 100, message = "Device label must be at most 100 characters")
        String deviceLabel
) {
}
