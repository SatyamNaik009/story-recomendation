package com.storystylist.backend.ai.config;
import com.storystylist.backend.ai.domain.AiProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("story.ai")
public record AiProperties(
        @DefaultValue("GEMINI") @NotNull AiProvider provider,
        @DefaultValue("90") @Min(1) @Max(300) int timeoutSeconds,
        @DefaultValue("1") @Min(0) @Max(3) int maxRetries,
        @DefaultValue("12582912") @Min(1) @Max(12582912) long maxImagePayloadBytes,
        @DefaultValue("8192") @Min(256) @Max(16384) int maxOutputTokens,
        @DefaultValue @Valid Gemini gemini,
        @DefaultValue @Valid OpenAi openai) {
    public record Gemini(@DefaultValue("gemini-3.6-flash") @NotBlank String model,
                         @DefaultValue("") @com.fasterxml.jackson.annotation.JsonIgnore String apiKey) {
        @Override public String toString() { return "Gemini[model=" + model + ", apiKey=<redacted>]"; }
    }
    public record OpenAi(@DefaultValue("gpt-6.1-terra") @NotBlank String model,
                         @DefaultValue("") @com.fasterxml.jackson.annotation.JsonIgnore String apiKey) {
        @Override public String toString() { return "OpenAi[model=" + model + ", apiKey=<redacted>]"; }
    }
}
