package com.storystylist.backend.ai.config;

import com.google.genai.Client;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.storystylist.backend.ai.VisionAiClient;
import com.storystylist.backend.ai.domain.AiProvider;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.ai.provider.*;
import com.storystylist.backend.ai.validation.VisionResponseCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import static com.storystylist.backend.ai.AiTestData.photos;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfiguration.class).withBean(VisionResponseCodec.class)
            .withPropertyValues("story.ai.gemini.api-key=", "story.ai.openai.api-key=");

    @Test void defaultsToGeminiAndStartsWithoutEitherKey() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(VisionAiClient.class);
            var client = context.getBean(VisionAiClient.class);
            assertThat(client).isInstanceOf(GeminiVisionClient.class);
            assertThat(client.provider()).isEqualTo(AiProvider.GEMINI);
            assertMissing(client);
        });
    }

    @ParameterizedTest @ValueSource(strings = {"gemini", "openai"})
    void selectedKeysModelsAndProviderComeFromBoundProperties(String provider) {
        // Intercept SDK construction: no network or real credentials are used.
        var geminiBuilder = mock(Client.Builder.class, RETURNS_SELF);
        var openaiBuilder = mock(OpenAIOkHttpClient.Builder.class, RETURNS_SELF);
        when(geminiBuilder.build()).thenReturn(mock(Client.class));
        when(openaiBuilder.build()).thenReturn(mock(OpenAIClient.class));
        try (var googleFactory = mockStatic(Client.class); var openaiFactory = mockStatic(OpenAIOkHttpClient.class)) {
            googleFactory.when(Client::builder).thenReturn(geminiBuilder);
            openaiFactory.when(OpenAIOkHttpClient::builder).thenReturn(openaiBuilder);
            String unused = provider.equals("gemini") ? "openai" : "gemini";
            runner.withPropertyValues("story.ai.provider=" + provider,
                    "story.ai." + provider + ".api-key=offline-configured-test-value",
                    "story.ai." + unused + ".api-key=YOUR_" + unused.toUpperCase() + "_API_KEY",
                    "story.ai.gemini.model=configured-gemini-model",
                    "story.ai.openai.model=configured-openai-model").run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(VisionAiClient.class);
                var client = context.getBean(VisionAiClient.class);
                assertThat(client.provider()).isEqualTo(AiProvider.valueOf(provider.toUpperCase()));
                var properties = context.getBean(AiProperties.class);
                assertThat(properties.gemini().model()).isEqualTo("configured-gemini-model");
                assertThat(properties.openai().model()).isEqualTo("configured-openai-model");
                if (provider.equals("gemini")) {
                    assertThat(client).isInstanceOf(GeminiVisionClient.class);
                    verify(geminiBuilder).apiKey("offline-configured-test-value");
                    openaiFactory.verifyNoInteractions();
                } else {
                    assertThat(client).isInstanceOf(OpenAiVisionClient.class);
                    verify(openaiBuilder).apiKey("offline-configured-test-value");
                    googleFactory.verifyNoInteractions();
                }
                assertThat(properties.toString()).doesNotContain("offline-configured-test-value");
                assertThat(JsonMapper.builder().build().writeValueAsString(properties))
                        .doesNotContain("offline-configured-test-value", "apiKey");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "YOUR_GEMINI_API_KEY", "YOUR_OPENAI_API_KEY", " your_gemini_api_key "})
    void missingAndPlaceholderSelectedKeysNeverInitializeEitherSdk(String key) {
        try (var googleFactory = mockStatic(Client.class); var openaiFactory = mockStatic(OpenAIOkHttpClient.class)) {
            for (String provider : new String[]{"gemini", "openai"}) {
                runner.withPropertyValues("story.ai.provider=" + provider,
                        "story.ai." + provider + ".api-key=" + key).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(VisionAiClient.class);
                    assertMissing(context.getBean(VisionAiClient.class));
                });
            }
            googleFactory.verifyNoInteractions();
            openaiFactory.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"gemini", "openai"})
    void legacyKeyVariablesDoNotReplaceMissingProperty(String provider) {
        runner.withPropertyValues("story.ai.provider=" + provider,
                "GEMINI_API_KEY=unused-test-value", "OPENAI_API_KEY=unused-test-value")
                .run(context -> assertMissing(context.getBean(VisionAiClient.class)));
    }

    @ParameterizedTest @ValueSource(strings = {"provider=invalid", "timeout-seconds=0", "max-retries=-1",
            "max-retries=4", "max-image-payload-bytes=12582913", "gemini.model=", "openai.model="})
    void invalidConfigurationFailsStartup(String setting) {
        runner.withPropertyValues("story.ai." + setting).run(context -> assertThat(context).hasFailed());
    }

    private void assertMissing(VisionAiClient client) {
        assertThatThrownBy(() -> client.analyze(photos(), null)).isInstanceOfSatisfying(AiException.class, error -> {
            assertThat(error.error()).isEqualTo(AiError.AI_CONFIGURATION_ERROR);
            assertThat(error.getCause()).isNull();
            assertThat(error.getMessage()).doesNotContain("test-value", "YOUR_");
        });
    }
}
