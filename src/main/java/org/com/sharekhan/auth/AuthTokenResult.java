package org.com.sharekhan.auth;

public record AuthTokenResult(String token, long expiresIn) {
}

