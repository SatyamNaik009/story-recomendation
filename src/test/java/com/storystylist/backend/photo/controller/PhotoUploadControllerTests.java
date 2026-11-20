package com.storystylist.backend.photo.controller;

import com.storystylist.backend.photo.TestImages;
import com.storystylist.backend.photo.service.PhotoValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PhotoUploadController.class)
@Import(PhotoValidationService.class)
class PhotoUploadControllerTests {
    @Autowired private MockMvc mvc;
    private static final String URL = "/api/v1/photos/inspect";

    @Test
    void returnsActualMetadataForMultiplePhotos() throws Exception {
        var jpeg = TestImages.photo("jpeg", 30, 20);
        var png = TestImages.photo("png", 40, 50);
        mvc.perform(multipart(URL).file(jpeg).file(png).param("prompt", "Summer"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.photoCount").value(2))
                .andExpect(jsonPath("$.promptProvided").value(true))
                .andExpect(jsonPath("$.photos[0].photoId").value("photo-1"))
                .andExpect(jsonPath("$.photos[0].mediaType").value("image/jpeg"))
                .andExpect(jsonPath("$.photos[0].sizeBytes").value(jpeg.getSize()))
                .andExpect(jsonPath("$.photos[0].width").value(30))
                .andExpect(jsonPath("$.photos[0].height").value(20))
                .andExpect(jsonPath("$.photos[1].photoId").value("photo-2"))
                .andExpect(jsonPath("$.photos[1].mediaType").value("image/png"))
                .andExpect(jsonPath("$.photos[1].width").value(40))
                .andExpect(jsonPath("$.photos[1].height").value(50))
                .andExpect(jsonPath("$.photos[0].originalFilename").doesNotExist());
    }

    @Test
    void missingPhotosReturnsStructuredError() throws Exception {
        mvc.perform(multipart(URL)).andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_PHOTOS"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void rejectsUnsupportedRequestContentType() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void rejectsInvalidContentWithoutExposingFilename() throws Exception {
        mvc.perform(multipart(URL).file(new MockMultipartFile("photos", "../../secret.jpg", "image/jpeg", new byte[]{1, 2})))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_IMAGE","message":"Only valid JPEG and PNG images are supported"}
                        """));
    }

    @Test
    void rejectsLongPrompt() throws Exception {
        mvc.perform(multipart(URL).file(TestImages.photo("png", 2, 3)).param("prompt", "a".repeat(301)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PROMPT"));
    }
}
