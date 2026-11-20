package com.storystylist.backend.ai.exception;
import org.springframework.http.HttpStatus;
public enum AiError {
    AI_CONFIGURATION_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "Selected AI provider credentials or model configuration are invalid"),
    AI_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Selected AI provider is unavailable"),
    AI_PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Selected AI provider timed out"),
    AI_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Selected AI provider rate or quota limit reached"),
    AI_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "Selected AI provider returned an invalid or incomplete analysis"),
    AI_PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Processed images exceed the configured AI payload limit");
    public final HttpStatus status;
    public final String message;
    AiError(HttpStatus status, String message) { this.status = status; this.message = message; }
}
