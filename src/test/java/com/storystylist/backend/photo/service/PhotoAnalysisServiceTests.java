package com.storystylist.backend.photo.service;
import com.storystylist.backend.ai.VisionAiClient;
import com.storystylist.backend.ai.config.AiProperties;
import com.storystylist.backend.ai.domain.*;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.ai.validation.VisionResultValidator;
import com.storystylist.backend.photo.TestImages;
import com.storystylist.backend.photo.config.*;
import com.storystylist.backend.photo.dto.ProcessedPhoto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.imageio.ImageIO;
import static com.storystylist.backend.ai.AiTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class PhotoAnalysisServiceTests {
    private final VisionAiClient client = mock(VisionAiClient.class);
    private final PhotoPreprocessingService preprocessing = new PhotoPreprocessingService(
            new PhotoValidationService(new PhotoValidationProperties(10, 10485760, 300, 25000000, 10000, 10000)),
            new PhotoProcessingProperties(1568, 1568, 0.85, "jpeg", false, true));
    private PhotoAnalysisService service(AiProperties properties) {
        return new PhotoAnalysisService(preprocessing, client, new VisionResultValidator(), properties);
    }
    @Test void preprocessesRealImagesInvokesOnlyInjectedClientAndPreservesOrder() throws Exception {
        when(client.provider()).thenReturn(AiProvider.GEMINI);
        when(client.analyze(anyList(), isNull())).thenReturn(new VisionAiResponse(
                analysis(List.of("photo-2", "photo-1")), "test-model", null));
        var result = service(settings(AiProvider.GEMINI)).analyze(
                List.of(TestImages.photo("png", 2000, 1000), TestImages.photo("jpeg", 20, 10)), "   ");
        assertThat(result.provider()).isEqualTo(AiProvider.GEMINI);
        assertThat(result.model()).isEqualTo("test-model");
        assertThat(result.photoCount()).isEqualTo(2);
        assertThat(result.photos()).extracting(PhotoObservation::photoId).containsExactly("photo-1", "photo-2");
        assertThat(result.usage()).isNull();
        assertThat(result.latencyMillis()).isNotNegative();
        @SuppressWarnings("unchecked") ArgumentCaptor<List<ProcessedPhoto>> capture = ArgumentCaptor.forClass(List.class);
        verify(client, times(1)).analyze(capture.capture(), isNull());
        var processed = capture.getValue();
        assertThat(processed.getFirst().metadata().processedWidth()).isEqualTo(1568);
        assertThat(ImageIO.read(new ByteArrayInputStream(processed.getFirst().bytes())).getHeight()).isEqualTo(784);
    }
    @Test void providerFailurePropagatesWithoutFallbackOrApplicationRetry() throws Exception {
        when(client.analyze(anyList(), any())).thenThrow(new AiException(AiError.AI_PROVIDER_TIMEOUT));
        assertThatThrownBy(() -> service(settings(AiProvider.GEMINI)).analyze(
                List.of(TestImages.photo("png", 20, 10)), null)).isInstanceOfSatisfying(AiException.class,
                e -> assertThat(e.error()).isEqualTo(AiError.AI_PROVIDER_TIMEOUT));
        verify(client, times(1)).analyze(anyList(), any());
        verifyNoMoreInteractions(client);
    }
    @Test void rejectsOversizedProcessedPayloadBeforeProviderCall() throws Exception {
        var standard = settings(AiProvider.GEMINI);
        var limited = new AiProperties(standard.provider(), 90, 1, 1, 8192, standard.gemini(), standard.openai());
        assertThatThrownBy(() -> service(limited).analyze(List.of(TestImages.photo("png", 20, 10)), null))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.error()).isEqualTo(AiError.AI_PAYLOAD_TOO_LARGE));
        verifyNoInteractions(client);
    }
    @Test void invalidModelReferencesAreNeverReturnedAsSuccess() throws Exception {
        when(client.analyze(anyList(), any())).thenReturn(new VisionAiResponse(analysis(List.of("unknown")), "model", null));
        assertThatThrownBy(() -> service(settings(AiProvider.GEMINI)).analyze(List.of(TestImages.photo("png", 20, 10)), null))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.error()).isEqualTo(AiError.AI_INVALID_RESPONSE));
    }
}
