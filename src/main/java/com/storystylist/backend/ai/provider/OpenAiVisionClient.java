package com.storystylist.backend.ai.provider;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.responses.*;
import com.storystylist.backend.ai.VisionAiClient;
import com.storystylist.backend.ai.config.AiProperties;
import com.storystylist.backend.ai.domain.*;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.ai.prompt.VisualAnalysisPrompt;
import com.storystylist.backend.ai.validation.*;
import com.storystylist.backend.photo.dto.ProcessedPhoto;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class OpenAiVisionClient implements VisionAiClient {
    private final OpenAIClient sdk;
    private final AiProperties settings;
    private final VisionResponseCodec codec;
    public OpenAiVisionClient(OpenAIClient sdk, AiProperties settings, VisionResponseCodec codec) {
        this.sdk = sdk; this.settings = settings; this.codec = codec;
    }
    @Override public AiProvider provider() { return AiProvider.OPENAI; }
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
        if (sdk == null) throw new AiException(AiError.AI_CONFIGURATION_ERROR);
        var content = new ArrayList<ResponseInputContent>();
        content.add(text(context));
        for (var photo : photos) {
            content.add(text("Photo ID: " + photo.metadata().photoId()));
            content.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .detail(ResponseInputImage.Detail.AUTO)
                    .imageUrl("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(photo.bytes())).build()));
        }
        var schema = ResponseFormatTextJsonSchemaConfig.Schema.builder();
        outputSchema.forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
        var format = ResponseFormatTextJsonSchemaConfig.builder().name(schemaName).strict(true)
                .schema(schema.build()).build();
        var request = ResponseCreateParams.builder().model(settings.openai().model()).store(false)
                .instructions(instructions).maxOutputTokens(settings.maxOutputTokens())
                .inputOfResponse(List.of(ResponseInputItem.ofMessage(ResponseInputItem.Message.builder()
                        .role(ResponseInputItem.Message.Role.USER).content(content).build())))
                .text(ResponseTextConfig.builder().format(format).build()).build();
        Response response;
        try { response = sdk.responses().create(request); }
        catch (OpenAIServiceException failure) { throw ProviderErrors.status(failure.statusCode()); }
        catch (OpenAIInvalidDataException failure) { throw new AiException(AiError.AI_INVALID_RESPONSE); }
        catch (RuntimeException failure) { throw ProviderErrors.transport(failure); }
        try {
            if (response == null || !ResponseStatus.COMPLETED.equals(response.status().orElse(null)))
                throw new AiException(AiError.AI_INVALID_RESPONSE);
            var texts = new ArrayList<String>();
            for (var item : response.output()) {
                if (item.isMessage()) {
                    for (var part : item.asMessage().content()) {
                        if (part.isRefusal()) throw new AiException(AiError.AI_INVALID_RESPONSE);
                        if (part.isOutputText()) texts.add(part.asOutputText().text());
                    }
                }
            }
            if (texts.size() != 1) throw new AiException(AiError.AI_INVALID_RESPONSE);
            var usage = response.usage().map(value -> new AiUsageMetadata(
                    value.inputTokens(), value.outputTokens(), value.totalTokens())).orElse(null);
            var model = response.model();
            String modelId = model.isString() ? model.asString()
                    : model.isChat() ? model.asChat().asString() : model.asOnly().asString();
            return new StructuredAiResponse(texts.getFirst(), modelId, usage);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).info("AI response envelope rejected: requestId={}, provider={}, rule=incompleteRefusedOrInvalidEnvelope",
                    org.slf4j.MDC.get("storyRequestId"), provider());
            throw new AiException(AiError.AI_INVALID_RESPONSE);
        }
    }
    private ResponseInputContent text(String text) {
        return ResponseInputContent.ofInputText(ResponseInputText.builder().text(text).build());
    }
    @Override public void close() { if (sdk != null) sdk.close(); }
}
