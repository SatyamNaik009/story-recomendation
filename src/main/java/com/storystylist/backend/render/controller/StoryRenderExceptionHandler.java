package com.storystylist.backend.render.controller;
import com.storystylist.backend.render.service.RenderRequestException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestControllerAdvice
public class StoryRenderExceptionHandler {
    public record ErrorResponse(String code,String message){}
    @ExceptionHandler(com.storystylist.backend.storage.StorageException.class)
    public ResponseEntity<ErrorResponse> storage(com.storystylist.backend.storage.StorageException e){return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(),e.getMessage()));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> malformed(){return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_JSON","Request body must be valid JSON"));}
    @ExceptionHandler(RenderRequestException.class)
    public ResponseEntity<ErrorResponse> handle(RenderRequestException e){return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(e.code(),e.getMessage()));}
}
