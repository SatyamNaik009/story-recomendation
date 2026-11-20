package com.storystylist.backend.photo.exception;

public class InvalidPhotoException extends RuntimeException {
    private final String code;

    public InvalidPhotoException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
