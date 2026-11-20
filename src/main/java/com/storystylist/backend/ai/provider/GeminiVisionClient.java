package com.storystylist.backend.ai.provider;
import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.errors.ApiException;
import com.google.genai.types.*;
import com.storystylist.backend.ai.VisionAiClient;
import com.storystylist.backend.ai.config.AiProperties;
import com.storystylist.backend.ai.domain.*;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.ai.prompt.VisualAnalysisPrompt;
import com.storystylist.backend.ai.validation.*;
import com.storystylist.backend.photo.dto.ProcessedPhoto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

public final class GeminiVisionClient implements VisionAiClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiVisionClient.class);
    private final Client sdk;
    private final Models models;
    private final AiProperties settings;
    private final VisionResponseCodec codec;
    public GeminiVisionClient(Client sdk, AiProperties settings, VisionResponseCodec codec) {
        this.sdk = sdk; this.models = sdk == null ? null : sdk.models; this.settings = settings; this.codec = codec;
    }
    // Package-local constructor for mocking the official SDK network boundary.
    GeminiVisionClient(Models models, AiProperties settings, VisionResponseCodec codec) {
        this.sdk = null; this.models = models; this.settings = settings; this.codec = codec;
    }
    @Override public AiProvider provider() { return AiProvider.GEMINI; }
    @Override public VisionAiResponse analyze(List<ProcessedPhoto> photos, String prompt) {
        VisionRequest.check(photos, prompt, settings.maxImagePayloadBytes());
        var response = generate(photos, VisualAnalysisPrompt.context(prompt), VisualAnalysisPrompt.INSTRUCTIONS,
                "visual_analysis_v1", codec.schema());
        return new VisionAiResponse(codec.parse(response.json()), response.model(), response.usage());
    }
    @Override public StructuredAiResponse generate(List<ProcessedPhoto> photos, String context, String instructions,
                                                   String schemaName, java.util.Map<String, Object> outputSchema) {
        VisionRequest.check(photos, null, settings.maxImagePayloadBytes());
        org.slf4j.LoggerFactory.getLogger(getClass()).info("AI generation started: operation={}, requestId={}, provider={}",
                "story_recommendation_v3".equals(schemaName) ? "STORY_RECOMMENDATION" : "VISUAL_ANALYSIS",
                org.slf4j.MDC.get("storyRequestId"), provider());
        if (models == null) throw new AiException(AiError.AI_CONFIGURATION_ERROR);
        var parts = new ArrayList<Part>();
        parts.add(Part.fromText(context));
        for (var photo : photos) {
            parts.add(Part.fromText("Photo ID: " + photo.metadata().photoId()));
            parts.add(Part.fromBytes(photo.bytes(), "image/jpeg"));
        }
        var content = Content.builder().role("user").parts(parts).build();
        var config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(instructions)))
                .responseMimeType("application/json").responseJsonSchema(outputSchema)
                .candidateCount(1).maxOutputTokens(settings.maxOutputTokens()).build();
        GenerateContentResponse response;
        long started = System.nanoTime();
        log.info("Gemini analysis request started: photoCount={}, timeoutSeconds={}, maxRetries={}",
                photos.size(), settings.timeoutSeconds(), settings.maxRetries());
        try { response = models.generateContent(settings.gemini().model(), content, config); }
        catch (ApiException failure) {
            var mapped = ProviderErrors.status(failure.code());
            logFailure(failure, failure.code(), mapped, started);
            throw mapped;
        }
        catch (RuntimeException failure) {
            var mapped = ProviderErrors.transport(failure);
            logFailure(failure, null, mapped, started);
            throw mapped;
        }
        try {
            if (response == null || response.candidates().orElse(List.of()).size() != 1
                    || response.finishReason() == null || response.finishReason().knownEnum() != FinishReason.Known.STOP)
                throw new AiException(AiError.AI_INVALID_RESPONSE);
            var usage = response.usageMetadata().map(value -> new AiUsageMetadata(
                    value.promptTokenCount().map(Integer::longValue).orElse(null),
                    value.candidatesTokenCount().map(Integer::longValue).orElse(null),
                    value.totalTokenCount().map(Integer::longValue).orElse(null))).orElse(null);
            return new StructuredAiResponse(response.text(),
                    response.modelVersion().orElse(settings.gemini().model()), usage);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).info("AI response envelope rejected: requestId={}, provider={}, rule=incompleteRefusedOrInvalidEnvelope",
                    org.slf4j.MDC.get("storyRequestId"), provider());
            throw new AiException(AiError.AI_INVALID_RESPONSE);
        }
    }
    private static void logFailure(RuntimeException failure, Integer httpStatus, AiException mapped, long started) {
        // Only class names: never pass a Throwable, message, headers, or body to the logger.
        var types = new ArrayList<String>();
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            types.add(cause.getClass().getName());
        }
        log.info("Gemini analysis request failed: httpStatus={}, exceptionTypes={}, errorCode={}, sdkDurationMs={}",
                httpStatus == null ? "unavailable" : httpStatus, types, mapped.error(),
                (System.nanoTime() - started) / 1_000_000);
    }
    @Override public void close() { if (sdk != null) sdk.close(); }
}
