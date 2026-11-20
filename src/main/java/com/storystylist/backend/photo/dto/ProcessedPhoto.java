package com.storystylist.backend.photo.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Request-local result for in-process consumers. The fresh byte array is owned by this
 * result; consumers must treat it as read-only. It is not copied on each access.
 * Controllers expose metadata separately, never this internal result as JSON.
 */
public record ProcessedPhoto(PhotoPreprocessingMetadata metadata, @JsonIgnore byte[] bytes) {
}
