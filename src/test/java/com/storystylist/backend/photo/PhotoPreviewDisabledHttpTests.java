package com.storystylist.backend.photo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "story.photo.processing.preview-enabled=false")
class PhotoPreviewDisabledHttpTests {
    @LocalServerPort private int port;

    @Test
    void disablingPreviewRemovesOnlyPreviewMapping() throws Exception {
        var photo = TestImages.image("png", 10, 20);
        var preview = PhotoPreprocessingHttpTests.upload(port, "preview", "photo", List.of(photo), null, "*/*");
        assertThat(preview.statusCode()).isEqualTo(404);
        var preprocess = PhotoPreprocessingHttpTests.upload(port, "preprocess", "photos", List.of(photo), null, "application/json");
        assertThat(preprocess.statusCode()).isEqualTo(200);
    }
}
