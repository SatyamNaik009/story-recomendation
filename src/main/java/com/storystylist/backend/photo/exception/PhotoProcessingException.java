package com.storystylist.backend.photo.exception;

public class PhotoProcessingException extends RuntimeException {
    public PhotoProcessingException(Throwable cause) {
        super("Unable to process the image", cause);
    }
}
