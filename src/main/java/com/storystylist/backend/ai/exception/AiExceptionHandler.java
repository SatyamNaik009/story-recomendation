package com.storystylist.backend.ai.exception;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
@RestControllerAdvice
public class AiExceptionHandler {
    public record ErrorResponse(String code, String message) {}
    @ExceptionHandler(AiException.class)
    public ResponseEntity<ErrorResponse> handle(AiException exception) {
        var error = exception.error();
        return ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(error.name(), error.message));
    }
}
