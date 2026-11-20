package com.storystylist.backend.ai.provider;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.storystylist.backend.ai.domain.AiProvider;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.ai.validation.VisionResponseCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static com.storystylist.backend.ai.AiTestData.*;
import static org.assertj.core.api.Assertions.*;

class OpenAiVisionClientTests {
    private OpenAiVisionClient client(StubProvider stub, int retries, int timeout) {
        var sdk = OpenAIOkHttpClient.builder().apiKey("offline-test-key").baseUrl(stub.url())
                .disableTelemetry(true).logLevel(com.openai.core.LogLevel.OFF)
                .maxRetries(retries).timeout(Duration.ofMillis(timeout)).build();
        return new OpenAiVisionClient(sdk, settings(AiProvider.OPENAI), new VisionResponseCodec());
    }
    @Test void generatesStoryAndPlanInOneMultimodalRequest() throws Exception {
        try (var stub = new StubProvider(); var client = client(stub, 0, 5000)) {
            String json = com.storystylist.backend.story.StoryTests.json();
            stub.response = success(json, "completed");
            var result = client.generate(photos(), "untrusted context",
                    com.storystylist.backend.story.ai.StoryRecommendationPrompt.INSTRUCTIONS,
                    "story_recommendation_v3", com.storystylist.backend.story.ai.StorySchema.schema());
            assertThat(result.json()).isEqualTo(json);
            assertThat(new VisionResponseCodec().parse(result.json(), com.storystylist.backend.story.dto.StoryAiResult.class)
                    .recommendations()).hasSize(1);
            assertThat(stub.requests).hasSize(1);
            assertThat(stub.requests.getFirst().toString()).contains("story-recommendation-v3", "renderPlan", "photo-1", "photo-2");
        }
    }
    private String success(String text, String status) {
        return JSON.writeValueAsString(Map.of("id", "resp_test", "object", "response", "created_at", 1,
                "status", status, "model", "gpt-6.1-terra", "output", List.of(Map.of("type", "message",
                        "id", "msg_test", "role", "assistant", "status", "completed", "content",
                        List.of(Map.of("type", "output_text", "text", text, "annotations", List.of())))),
                "usage", Map.of("input_tokens", 10, "output_tokens", 20, "total_tokens", 30,
                        "input_tokens_details", Map.of("cached_tokens", 0), "output_tokens_details", Map.of("reasoning_tokens", 0))));
    }
    @Test void sendsOneResponsesRequestWithDataUrlsStrictSchemaAndStoreFalse() throws Exception {
        try (var stub = new StubProvider(); var client = client(stub, 0, 5000)) {
            stub.response = success(json(), "completed");
            var photos = photos();
            var response = client.analyze(photos, null);
            assertThat(response.model()).isEqualTo("gpt-6.1-terra");
            assertThat(response.analysis().photos()).hasSize(2);
            assertThat(response.usage().inputTokens()).isEqualTo(10);
            assertThat(stub.requests).hasSize(1);
            var request = stub.requests.getFirst();
            assertThat(request.path("store").asBoolean()).isFalse();
            assertThat(request.path("instructions").asString()).contains("UNTRUSTED DATA");
            var content = request.path("input").get(0).path("content");
            assertThat(content.size()).isEqualTo(5);
            for (int i = 0; i < 2; i++) {
                assertThat(content.get(i * 2 + 1).path("text").asString()).contains(photos.get(i).metadata().photoId());
                String uri = content.get(i * 2 + 2).path("image_url").asString();
                assertThat(uri).startsWith("data:image/jpeg;base64,");
                assertThat(Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1))).isEqualTo(photos.get(i).bytes());
            }
            var format = request.path("text").path("format");
            assertThat(format.path("type").asString()).isEqualTo("json_schema");
            assertThat(format.path("strict").asBoolean()).isTrue();
            assertThat(format.path("schema").path("additionalProperties").asBoolean()).isFalse();
        }
    }
    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503, 504})
    void mapsProviderErrors(int status) throws Exception {
        try (var stub = new StubProvider(); var client = client(stub, 0, 5000)) {
            stub.status = status; stub.response = "{\"error\":{\"message\":\"secret-provider-detail\",\"type\":\"api_error\"}}";
            assertThatThrownBy(() -> client.analyze(photos(), null)).isInstanceOfSatisfying(AiException.class, e -> {
                assertThat(e.error()).isEqualTo(ProviderErrors.status(status).error());
                assertThat(e.getMessage()).doesNotContain("secret");
                assertThat(e.getCause()).isNull();
            });
            assertThat(stub.requests).hasSize(1);
        }
    }
    @Test void retriesRemainBoundedAndDoNotRetryAuthentication() throws Exception {
        try (var stub = new StubProvider(); var client = client(stub, 1, 5000)) {
            stub.status = 429; stub.response = "{\"error\":{\"message\":\"quota\"}}";
            assertThatThrownBy(() -> client.analyze(photos(), null)).isInstanceOf(AiException.class);
            assertThat(stub.requests).hasSize(2);
            stub.requests.clear(); stub.status = 401;
            assertThatThrownBy(() -> client.analyze(photos(), null)).isInstanceOf(AiException.class);
            assertThat(stub.requests).hasSize(1);
        }
    }
    @Test void handlesTimeoutAndMalformedOrIncompleteResponses() throws Exception {
        try (var stub = new StubProvider(); var client = client(stub, 0, 100)) {
            stub.delayMillis = 500;
            assertThatThrownBy(() -> client.analyze(photos(), null)).isInstanceOfSatisfying(AiException.class,
                    e -> assertThat(e.error()).isEqualTo(AiError.AI_PROVIDER_TIMEOUT));
        }
        try (var stub = new StubProvider(); var client = client(stub, 0, 5000)) {
            String refusal = success("Cannot analyze", "completed")
                    .replace("\"type\":\"output_text\"", "\"type\":\"refusal\"")
                    .replace("\"text\":", "\"refusal\":");
            for (String response : List.of(success("not json", "completed"), success(json(), "incomplete"), refusal, "{}")) {
                stub.response = response;
                assertThatThrownBy(() -> client.analyze(photos(), null)).isInstanceOfSatisfying(AiException.class,
                        e -> assertThat(e.error()).isEqualTo(AiError.AI_INVALID_RESPONSE));
            }
        }
    }
    @Test void missingCredentialsFailWithoutNetwork() {
        var client = new OpenAiVisionClient(null, settings(AiProvider.OPENAI), new VisionResponseCodec());
        assertThatThrownBy(() -> client.analyze(photos(), null)).isInstanceOfSatisfying(AiException.class,
                e -> assertThat(e.error()).isEqualTo(AiError.AI_CONFIGURATION_ERROR));
    }

    @Test void absentUsageRemainsAbsentAndNetworkFailuresAreSafe() throws Exception {
        try (var stub = new StubProvider(); var client = client(stub, 0, 1000)) {
            var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(success(json(), "completed"));
            body.remove("usage"); stub.response = body.toString();
            assertThat(client.analyze(photos(), null).usage()).isNull();
            stub.close();
            assertThatThrownBy(() -> client.analyze(photos(), null)).isInstanceOfSatisfying(AiException.class,
                    e -> assertThat(e.error()).isEqualTo(AiError.AI_PROVIDER_UNAVAILABLE));
        }
    }
}
