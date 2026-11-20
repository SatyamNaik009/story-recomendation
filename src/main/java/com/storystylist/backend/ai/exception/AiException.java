package com.storystylist.backend.ai.exception;
/** Deliberately does not retain provider response bodies, headers, or exception messages. */
public class AiException extends RuntimeException {
    private final AiError error;
    public AiException(AiError error) { super(error.message); this.error = error; }
    public AiError error() { return error; }
}
