package com.storystylist.backend.photo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhotoPreprocessingHttpTests {
    @LocalServerPort private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preprocessReturnsOnlyAccurateMetadataInUploadOrder() throws Exception {
        var response = upload(port, "preprocess", "photos", List.of(
                ProcessingTestImages.orientedJpeg(120, 80, 6), TestImages.image("png", 2000, 1000)), "Summer", "application/json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        var json = mapper.readTree(response.body());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.path("status").asString()).isEqualTo("PROCESSED");
        assertThat(json.path("photoCount").asInt()).isEqualTo(2);
        assertThat(json.path("photos").size()).isEqualTo(2);
        var first = json.path("photos").get(0);
        assertThat(first.size()).isEqualTo(8);
        assertThat(first.path("photoId").asString()).isEqualTo("photo-1");
        assertThat(first.path("originalWidth").asInt()).isEqualTo(120);
        assertThat(first.path("originalHeight").asInt()).isEqualTo(80);
        assertThat(first.path("processedWidth").asInt()).isEqualTo(80);
        assertThat(first.path("processedHeight").asInt()).isEqualTo(120);
        assertThat(first.path("orientationCorrected").asBoolean()).isTrue();
        assertThat(first.path("processedMediaType").asString()).isEqualTo("image/jpeg");
        assertThat(first.path("processedSizeBytes").asInt()).isPositive();
        var second = json.path("photos").get(1);
        assertThat(second.path("photoId").asString()).isEqualTo("photo-2");
        assertThat(second.path("processedWidth").asInt()).isEqualTo(1568);
        assertThat(second.path("processedHeight").asInt()).isEqualTo(784);
        assertThat(second.path("orientationCorrected").asBoolean()).isFalse();
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotContain("base64", "filename", "bytes\"");
    }

    @Test
    void previewReturnsDecodableJpegMatchingPreprocessMetadata() throws Exception {
        byte[] source = ProcessingTestImages.orientedJpeg(2400, 1600, 8);
        var response = upload(port, "preview", "photo", List.of(source), null, "image/jpeg");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Content-Disposition")).hasValue("inline; filename=\"preview.jpg\"");
        assertThat(response.headers().firstValueAsLong("Content-Length").orElseThrow()).isEqualTo(response.body().length);
        assertThat(response.body()).isNotEmpty();
        var image = ImageIO.read(new ByteArrayInputStream(response.body()));
        assertThat(image.getWidth()).isEqualTo(1045);
        assertThat(image.getHeight()).isEqualTo(1568);
        var metadataResponse = upload(port, "preprocess", "photos", List.of(source), null, "application/json");
        var metadata = mapper.readTree(metadataResponse.body()).path("photos").get(0);
        assertThat(metadata.path("processedSizeBytes").asInt()).isEqualTo(response.body().length);
        assertThat(metadata.path("processedWidth").asInt()).isEqualTo(image.getWidth());
        assertThat(metadata.path("processedHeight").asInt()).isEqualTo(image.getHeight());
    }

    @Test
    void rejectsInvalidUploadsAndPromptWithStructuredErrors() throws Exception {
        assertError(upload(port, "preprocess", "photos", List.of(new byte[]{1, 2}), null, "application/json"), 400, "INVALID_IMAGE");
        assertError(upload(port, "preprocess", "photos", List.of(), "text", "application/json"), 400, "MISSING_PHOTOS");
        assertError(upload(port, "preprocess", "photos", List.of(TestImages.image("png", 20, 10)),
                "x".repeat(301), "application/json"), 400, "INVALID_PROMPT");
    }

    @Test
    void rejectsCorruptPixelsEvenThoughInspectionAcceptsHeader() throws Exception {
        byte[] damaged = Arrays.copyOf(TestImages.image("png", 100, 80), 45);
        assertThat(upload(port, "inspect", "photos", List.of(damaged), null, "application/json").statusCode()).isEqualTo(200);
        assertError(upload(port, "preprocess", "photos", List.of(damaged), null, "application/json"), 400, "INVALID_IMAGE");
    }

    @Test
    void previewRequiresExactlyOneFileAndReturnsJsonErrorsDespiteImageAcceptHeader() throws Exception {
        var photo = TestImages.image("png", 20, 10);
        assertError(upload(port, "preview", "photo", List.of(photo, photo), null, "image/jpeg"), 400, "INVALID_PHOTO_COUNT");
        assertError(upload(port, "preview", "photo", List.of(), null, "image/jpeg"), 400, "MISSING_PHOTOS");
        assertError(upload(port, "preview", "photo", List.of(new byte[]{1}), null, "image/jpeg"), 400, "INVALID_IMAGE");
    }

    @Test
    void previewRetainsActualServletFileSizeEnforcement() throws Exception {
        assertError(upload(port, "preview", "photo", List.of(new byte[10485761]), null, "image/jpeg"), 413, "UPLOAD_TOO_LARGE");
    }

    private void assertError(HttpResponse<byte[]> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        var body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("message").asString()).isNotBlank();
    }

    static HttpResponse<byte[]> upload(int port, String endpoint, String field, List<byte[]> files,
                                       String prompt, String accept) throws Exception {
        String boundary = "preprocessing-test-boundary";
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        for (byte[] file : files) {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"untrusted.bin\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(file));
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
        }
        if (prompt != null) {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n" + prompt + "\r\n"));
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n"));
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/photos/" + endpoint))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("Accept", accept)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new))).build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
    }
}
