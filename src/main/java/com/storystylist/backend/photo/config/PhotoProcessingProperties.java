package com.storystylist.backend.photo.config;

import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("story.photo.processing")
public record PhotoProcessingProperties(
        @DefaultValue("1568") @Min(1) @Max(10000) int maxWidth,
        @DefaultValue("1568") @Min(1) @Max(10000) int maxHeight,
        @DefaultValue("0.85") @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") double jpegQuality,
        @DefaultValue("jpeg") @Pattern(regexp = "jpeg") String outputFormat,
        @DefaultValue("false") @AssertFalse(message = "Upscaling is not supported") boolean allowUpscaling,
        @DefaultValue("false") boolean previewEnabled) {
}
