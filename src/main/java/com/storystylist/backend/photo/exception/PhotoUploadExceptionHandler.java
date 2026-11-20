package com.storystylist.backend.photo.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

// Global advice is necessary for multipart failures raised before controller selection.
// Handles photo validation/processing, multipart parsing, and unsupported media type errors.
@RestControllerAdvice
public class PhotoUploadExceptionHandler {
    public record UploadError(String code, String message) {
    }

    @ExceptionHandler(InvalidPhotoException.class)
    public ResponseEntity<UploadError> invalidPhoto(InvalidPhotoException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<UploadError> tooLarge() {
        return error(HttpStatus.CONTENT_TOO_LARGE, "UPLOAD_TOO_LARGE", "File or request exceeds the configured upload limit");
    }

    @ExceptionHandler(PhotoProcessingException.class)
    public ResponseEntity<UploadError> processingFailed() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_PROCESSING_FAILED", "Unable to process the image");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<UploadError> malformedMultipart() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_MULTIPART", "Malformed multipart upload");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<UploadError> unsupportedMediaType() {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Use multipart/form-data for photo uploads");
    }

    private ResponseEntity<UploadError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new UploadError(code, message));
    }
}
