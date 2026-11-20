package com.storystylist.backend.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhotoUploadHttpTests {
    private static final String BOUNDARY = "story-stylist-test-boundary";
    @LocalServerPort private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realServerParsesFilesAndTextAndPreservesHealthEndpoint(boolean expectContinue) throws Exception {
        var jpeg = TestImages.image("jpeg", 12, 34);
        var png = TestImages.image("png", 56, 78);
        var response = upload(List.of(jpeg, png), "Summer", expectContinue);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("status").asString()).isEqualTo("VALID");
        assertThat(body.path("photoCount").asInt()).isEqualTo(2);
        assertThat(body.path("promptProvided").asBoolean()).isTrue();
        assertThat(body.path("photos").get(0).path("photoId").asString()).isEqualTo("photo-1");
        assertThat(body.path("photos").get(0).path("sizeBytes").asLong()).isEqualTo(jpeg.length);
        assertThat(body.path("photos").get(1).path("photoId").asString()).isEqualTo("photo-2");
        assertThat(body.path("photos").get(1).path("width").asInt()).isEqualTo(56);
        try (var client = HttpClient.newHttpClient()) {
            var health = client.send(HttpRequest.newBuilder(uri("/api/v1/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(health.body()).path("status").asString()).isEqualTo("UP");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void servletRejectsFileOverActualTenMegabyteLimit(boolean expectContinue) throws Exception {
        assertError(upload(List.of(new byte[10 * 1024 * 1024 + 1]), null, expectContinue), 413, "UPLOAD_TOO_LARGE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void servletRejectsRequestOverActual110MegabyteLimit(boolean expectContinue) throws Exception {
        // Reuse one buffer; the publisher streams the parts without aggregating 110 MB in memory.
        // Each file is within its limit; eleven files plus framing exceed the total request limit.
        byte[] file = new byte[10 * 1024 * 1024];
        assertError(upload(java.util.Collections.nCopies(11, file), null, expectContinue), 413, "UPLOAD_TOO_LARGE");
    }

    @Test
    void realServerAcceptsTenFilesAndRejectsElevenSmallFiles() throws Exception {
        var image = TestImages.image("png", 2, 3);
        assertThat(upload(java.util.Collections.nCopies(10, image), "test").statusCode()).isEqualTo(200);
        assertError(upload(java.util.Collections.nCopies(11, image), null), 400, "TOO_MANY_PHOTOS");
    }

    @Test
    void realServerRejectsMissingPhotos() throws Exception {
        assertError(upload(List.of(), "test"), 400, "MISSING_PHOTOS");
    }

    @Test
    void malformedMultipartReturnsSafeError() throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/photos/inspect"))
                .header("Content-Type", "multipart/form-data")
                .POST(HttpRequest.BodyPublishers.ofString("broken multipart"))
                .timeout(Duration.ofSeconds(20)).build();
        try (var client = HttpClient.newHttpClient()) {
            assertError(client.send(request, HttpResponse.BodyHandlers.ofString()), 400, "INVALID_MULTIPART");
        }
    }

    private HttpResponse<String> upload(List<byte[]> files, String prompt) throws Exception {
        return upload(files, prompt, false);
    }

    private HttpResponse<String> upload(List<byte[]> files, String prompt, boolean expectContinue) throws Exception {
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        for (byte[] file : files) {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"photos\"; filename=\"untrusted.bin\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(file));
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
        }
        if (prompt != null) {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n" + prompt + "\r\n"));
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + BOUNDARY + "--\r\n"));
        var request = HttpRequest.newBuilder(uri("/api/v1/photos/inspect"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .expectContinue(expectContinue).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new))).build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        var body = mapper.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("message").asString()).isNotBlank();
        assertThat(body.size()).isEqualTo(2);
    }
}
