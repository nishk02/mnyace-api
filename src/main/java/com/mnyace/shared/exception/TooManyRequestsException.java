package com.mnyace.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** A 429 that tells the client when to retry, sent as the Retry-After header. */
@Getter
public class TooManyRequestsException extends ApiException {
    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
