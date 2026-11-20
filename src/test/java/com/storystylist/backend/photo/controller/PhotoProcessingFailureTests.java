package com.storystylist.backend.photo.controller;

import com.storystylist.backend.photo.TestImages;
import com.storystylist.backend.photo.exception.PhotoProcessingException;
import com.storystylist.backend.photo.service.PhotoPreprocessingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PhotoPreprocessingController.class)
class PhotoProcessingFailureTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private PhotoPreprocessingService preprocessing;

    @Test
    void unexpectedProcessingFailureReturnsSanitizedServerError() throws Exception {
        when(preprocessing.preprocess(any(), any())).thenThrow(new PhotoProcessingException(
                new IllegalStateException("Sensitive internal path /private/file")));
        mvc.perform(multipart("/api/v1/photos/preprocess").file(TestImages.photo("png", 10, 20)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                        {"code":"IMAGE_PROCESSING_FAILED","message":"Unable to process the image"}
                        """));
    }
}
