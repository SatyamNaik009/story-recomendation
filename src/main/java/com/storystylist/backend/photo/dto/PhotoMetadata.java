package com.storystylist.backend.photo.dto;

public record PhotoMetadata(String photoId, String mediaType, long sizeBytes, int width, int height) {
}
