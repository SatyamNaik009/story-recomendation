package com.storystylist.backend.story.dto;
public record PhotoAssetMetadata(String photoId, String sha256, Integer originalWidth, Integer originalHeight, String mediaType, Integer orientedWidth, Integer orientedHeight) {}
