package com.storystylist.backend.photo.service;
import com.storystylist.backend.ai.VisionAiClient;
import com.storystylist.backend.ai.config.AiProperties;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.ai.prompt.VisualAnalysisPrompt;
import com.storystylist.backend.ai.validation.*;
import com.storystylist.backend.photo.dto.PhotoAnalysisResponse;
import com.storystylist.backend.photo.exception.InvalidPhotoException;
import com.storystylist.backend.photo.exception.PhotoProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.UUID;
@Service
public class PhotoAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(PhotoAnalysisService.class);
    private final PhotoPreprocessingService preprocessing;
    private final VisionAiClient client;
    private final VisionResultValidator validator;
    private final AiProperties settings;
    public PhotoAnalysisService(PhotoPreprocessingService preprocessing, VisionAiClient client,
                                VisionResultValidator validator, AiProperties settings) {
        this.preprocessing = preprocessing; this.client = client; this.validator = validator; this.settings = settings;
    }
    public PhotoAnalysisResponse analyze(List<MultipartFile> uploads, String prompt) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        log.info("Photo analysis started: requestId={}, provider={}, photoCount={}",
                requestId, settings.provider(), uploads == null ? 0 : uploads.size());
        try {
            var result = performAnalysis(uploads, prompt);
            var usage = result.usage();
            log.info("Photo analysis completed: requestId={}, provider={}, photoCount={}, totalDurationMs={}, aiDurationMs={}, inputTokens={}, outputTokens={}, totalTokens={}",
                    requestId, result.provider(), result.photoCount(), elapsedMillis(started), result.latencyMillis(),
                    usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(),
                    usage == null ? null : usage.totalTokens());
            return result;
        } catch (RuntimeException exception) {
            // Log only application error codes, never exception messages or provider payloads.
            String code = switch (exception) {
                case AiException ai -> ai.error().name();
                case InvalidPhotoException ignored -> "PHOTO_VALIDATION_FAILED";
                case PhotoProcessingException ignored -> "PHOTO_PROCESSING_FAILED";
                default -> "INTERNAL_ERROR";
            };
            log.info("Photo analysis failed: requestId={}, provider={}, durationMs={}, errorCode={}",
                    requestId, settings.provider(), elapsedMillis(started), code);
            throw exception;
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private PhotoAnalysisResponse performAnalysis(List<MultipartFile> uploads, String prompt) {
        var photos = preprocessing.preprocess(uploads, prompt);
        VisionRequest.check(photos, prompt, settings.maxImagePayloadBytes());
        long started = System.nanoTime();
        var response = client.analyze(photos, prompt == null || prompt.isBlank() ? null : prompt);
        long latency = (System.nanoTime() - started) / 1_000_000;
        if (response == null || response.model() == null || response.model().isBlank())
            throw new AiException(AiError.AI_INVALID_RESPONSE);
        var result = validator.validate(response.analysis(), photos);
        return new PhotoAnalysisResponse("ANALYZED", client.provider(), response.model(), photos.size(),
                result.overallMood(), result.photos(), result.groupAnalysis(), latency, response.usage(), VisualAnalysisPrompt.VERSION);
    }
}
