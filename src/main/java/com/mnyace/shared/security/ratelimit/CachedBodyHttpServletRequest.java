package com.mnyace.shared.security.ratelimit;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Lets the request body be read twice (filter, then controller), capped at maxBytes because it runs before authentication. */
class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    static class BodyTooLargeException extends IOException {
        BodyTooLargeException(int maxBytes) {
            super("Request body exceeds " + maxBytes + " bytes");
        }
    }

    CachedBodyHttpServletRequest(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        if (request.getContentLengthLong() > maxBytes) {
            throw new BodyTooLargeException(maxBytes);
        }
        // Chunked bodies carry no Content-Length, so read one byte past the cap to detect overflow.
        byte[] read = request.getInputStream().readNBytes(maxBytes + 1);
        if (read.length > maxBytes) {
            throw new BodyTooLargeException(maxBytes);
        }
        this.body = read;
    }

    byte[] getBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // Not needed for this synchronous, fully-buffered stream.
            }

            @Override
            public int read() {
                return byteArrayInputStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}