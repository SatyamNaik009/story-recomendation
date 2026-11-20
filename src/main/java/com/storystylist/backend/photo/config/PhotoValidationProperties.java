package com.storystylist.backend.photo.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("photo.validation")
public record PhotoValidationProperties(
        @DefaultValue("10") @Min(1) @Max(10) int maxPhotos,
        @DefaultValue("10485760") @Min(1) @Max(10485760) long maxFileSizeBytes,
        @DefaultValue("300") @Min(1) @Max(300) int maxPromptLength,
        @DefaultValue("25000000") @Min(1) long maxPixels,
        @DefaultValue("10000") @Min(1) int maxWidth,
        @DefaultValue("10000") @Min(1) int maxHeight) {
}
