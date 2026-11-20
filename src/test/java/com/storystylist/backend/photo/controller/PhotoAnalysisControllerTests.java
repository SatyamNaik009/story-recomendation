package com.storystylist.backend.photo.controller;
import com.storystylist.backend.ai.VisionAiClient;
import com.storystylist.backend.ai.config.AiProperties;
import com.storystylist.backend.ai.domain.*;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.ai.validation.VisionResultValidator;
import com.storystylist.backend.photo.TestImages;
import com.storystylist.backend.photo.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static com.storystylist.backend.ai.AiTestData.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(PhotoAnalysisController.class)
@Import({PhotoAnalysisService.class, PhotoPreprocessingService.class, PhotoValidationService.class,
        VisionResultValidator.class, PhotoAnalysisControllerTests.Properties.class})
class PhotoAnalysisControllerTests {
    @TestConfiguration @EnableConfigurationProperties(AiProperties.class) static class Properties {}
    @Autowired MockMvc mvc;
    @MockitoBean VisionAiClient client;
    @ParameterizedTest @EnumSource(AiProvider.class)
    void analyzesRealMultipartImagesForBothProviderAttributions(AiProvider provider) throws Exception {
        when(client.provider()).thenReturn(provider);
        String model = provider == AiProvider.GEMINI ? "gemini-3.6-flash" : "gpt-6.1-terra";
        when(client.analyze(anyList(), eq("Summer"))).thenAnswer(call -> {
            List<com.storystylist.backend.photo.dto.ProcessedPhoto> images = call.getArgument(0);
            org.assertj.core.api.Assertions.assertThat(images).hasSize(2);
            org.assertj.core.api.Assertions.assertThat(images.getFirst().metadata().processedWidth()).isEqualTo(1568);
            return new VisionAiResponse(analysis(List.of("photo-1", "photo-2")), model, new AiUsageMetadata(10L, 20L, 30L));
        });
        mvc.perform(multipart("/api/v1/photos/analyze").file(TestImages.photo("png", 2000, 1000))
                        .file(TestImages.photo("jpeg", 20, 10)).param("prompt", "Summer"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value("ANALYZED")).andExpect(jsonPath("$.provider").value(provider.name()))
                .andExpect(jsonPath("$.model").value(model)).andExpect(jsonPath("$.photoCount").value(2))
                .andExpect(jsonPath("$.photos[0].photoId").value("photo-1"))
                .andExpect(jsonPath("$.photos[1].photoId").value("photo-2"))
                .andExpect(jsonPath("$.usage.totalTokens").value(30)).andExpect(jsonPath("$.latencyMillis").isNumber());
        verify(client, times(1)).analyze(anyList(), eq("Summer"));
    }
    @Test void validatesUploadsBeforeInvokingProvider() throws Exception {
        mvc.perform(multipart("/api/v1/photos/analyze")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PHOTOS"));
        mvc.perform(multipart("/api/v1/photos/analyze").file(TestImages.photo("png", 20, 10)).param("prompt", "x".repeat(301)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PROMPT"));
        verifyNoInteractions(client);
    }
    @Test void returnsSafeProviderAndInvalidResponseErrors() throws Exception {
        for (AiError error : List.of(AiError.AI_PROVIDER_TIMEOUT, AiError.AI_RATE_LIMITED, AiError.AI_CONFIGURATION_ERROR,
                AiError.AI_PROVIDER_UNAVAILABLE, AiError.AI_INVALID_RESPONSE)) {
            doThrow(new AiException(error)).when(client).analyze(anyList(), any());
            mvc.perform(multipart("/api/v1/photos/analyze").file(TestImages.photo("png", 20, 10)))
                    .andExpect(status().is(error.status.value())).andExpect(jsonPath("$.code").value(error.name()));
        }
    }
    @Test void rejectsUnknownPhotoIdsAndUnsupportedRequestType() throws Exception {
        when(client.analyze(anyList(), any())).thenReturn(new VisionAiResponse(analysis(List.of("unknown")), "model", null));
        mvc.perform(multipart("/api/v1/photos/analyze").file(TestImages.photo("png", 20, 10)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_INVALID_RESPONSE"));
        mvc.perform(post("/api/v1/photos/analyze").contentType("application/json").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
