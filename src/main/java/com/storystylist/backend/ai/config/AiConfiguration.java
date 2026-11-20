package com.storystylist.backend.ai.config;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.storystylist.backend.ai.VisionAiClient;
import com.storystylist.backend.ai.provider.*;
import com.storystylist.backend.ai.validation.VisionResponseCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
    @Bean(destroyMethod = "close")
    public VisionAiClient visionAiClient(AiProperties settings, VisionResponseCodec codec) {
        // Exactly one adapter/SDK is constructed. Never discover credentials from the other provider.
        return switch (settings.provider()) {
            case GEMINI -> {
                String key = configuredKey(settings.gemini().apiKey());
                Client sdk = key == null ? null : Client.builder().apiKey(key).enterprise(false)
                        .httpOptions(HttpOptions.builder().apiVersion("v1beta")
                                .timeout(settings.timeoutSeconds() * 1000)
                                .retryOptions(HttpRetryOptions.builder().attempts(settings.maxRetries() + 1)
                                        .httpStatusCodes(408, 429, 500, 502, 503, 504).maxDelay(2.0).build()).build()).build();
                yield new GeminiVisionClient(sdk, settings, codec);
            }
            case OPENAI -> {
                String key = configuredKey(settings.openai().apiKey());
                var sdk = key == null ? null : OpenAIOkHttpClient.builder().apiKey(key)
                        .logLevel(com.openai.core.LogLevel.OFF).disableTelemetry(true)
                        .timeout(Duration.ofSeconds(settings.timeoutSeconds())).maxRetries(settings.maxRetries()).build();
                yield new OpenAiVisionClient(sdk, settings, codec);
            }
        };
    }

    private String configuredKey(String value) {
        if (value == null || value.isBlank()) return null;
        String key = value.strip();
        return key.equalsIgnoreCase("YOUR_GEMINI_API_KEY") || key.equalsIgnoreCase("YOUR_OPENAI_API_KEY")
                ? null : key;
    }
}
