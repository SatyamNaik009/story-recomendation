package com.storystylist.backend.storage;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
@Validated @ConfigurationProperties("story.storage")
public record StorageProperties(@DefaultValue("24") @Min(1) @Max(168) int retentionHours) {}
