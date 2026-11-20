package com.storystylist.backend.story.service;
import com.storystylist.backend.ai.*;
import com.storystylist.backend.ai.config.AiProperties;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.ai.prompt.VisualAnalysisPrompt;
import com.storystylist.backend.ai.validation.*;
import com.storystylist.backend.photo.service.PhotoPreprocessingService;
import com.storystylist.backend.photo.exception.PhotoProcessingException;
import com.storystylist.backend.story.ai.*;
import com.storystylist.backend.story.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.security.*;
import java.util.*;
@Service
public class StoryRecommendationService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(StoryRecommendationService.class);
    private final PhotoPreprocessingService preprocessing;
    private final VisionAiClient client;
    private final VisionResponseCodec codec;
    private final RecommendationValidationService validation;
    private final AiProperties settings;
    private final com.storystylist.backend.storage.StoryStorageService storage;
    public StoryRecommendationService(PhotoPreprocessingService preprocessing, VisionAiClient client,
            VisionResponseCodec codec, RecommendationValidationService validation, AiProperties settings, com.storystylist.backend.storage.StoryStorageService storage) {
        this.storage=storage;
        this.preprocessing=preprocessing; this.client=client; this.codec=codec; this.validation=validation; this.settings=settings;
    }
    public StoryRecommendationResponse recommend(List<MultipartFile> uploads, String prompt) {
        String requestId=UUID.randomUUID().toString();
        String previous=org.slf4j.MDC.get("storyRequestId");
        org.slf4j.MDC.put("storyRequestId",requestId);
        long overallStart=System.nanoTime();
        String stage="PHOTO_PROCESSING";
        log.info("Story recommendation started: requestId={}, provider={}, photoCount={}",requestId,settings.provider(),uploads==null?0:uploads.size());
        try {
        var photos=preprocessing.preprocess(uploads,prompt);
        VisionRequest.check(photos,prompt,settings.maxImagePayloadBytes());
        var assets=new ArrayList<PhotoAssetMetadata>();
        for(int i=0;i<photos.size();i++) {
            assets.add(com.storystylist.backend.photo.service.PhotoAssetReader.read(uploads.get(i),photos.get(i).metadata().photoId()));
        }

        String context=VisualAnalysisPrompt.context(prompt)+"\nOriented source dimensions: "+assets.stream()
                .map(a->a.photoId()+"="+a.orientedWidth()+"x"+a.orientedHeight()).toList();
        long start=System.nanoTime();
        stage="PROVIDER_RESPONSE";
        var response=client.generate(photos,context,StoryRecommendationPrompt.INSTRUCTIONS+"\nSupported font sizes: "+validation.textSettings().minFontSize()+".."+validation.textSettings().maxFontSize()+" pixels (use this configured range).","story_recommendation_v3",StorySchema.schema());
        long latency=(System.nanoTime()-start)/1_000_000;
        stage="RESPONSE_METADATA";
        if(response==null || response.model()==null || response.model().isBlank()) throw new AiException(AiError.AI_INVALID_RESPONSE);
        stage="JSON_PARSING";
        var parsed=codec.parse(response.json(),StoryAiResult.class);
        stage="RENDER_PLAN_NORMALIZATION";
        var finalized=RenderPlanNormalizer.normalize(parsed,assets,validation);
        var drafts=finalized.recommendations();
        var recommendations=drafts.stream().map(d->new StoryRecommendation(UUID.randomUUID().toString(),d.summary(),d.renderPlan())).toList();
        stage="PERSISTENCE";
        var result=new StoryRecommendationResponse("RECOMMENDED",requestId,client.provider(),response.model(),photos.size(),
                recommendations,List.copyOf(assets),latency,response.usage(),StoryRecommendationPrompt.VERSION);
        storage.save(result,uploads);
        log.info("Story recommendation completed: requestId={}, recommendationCount={}, aiDurationMs={}, totalDurationMs={}",
                requestId,recommendations.size(),latency,(System.nanoTime()-overallStart)/1_000_000);
        return result;
        } catch (RuntimeException failure) {
            String rule=failure instanceof InvalidRecommendationException invalid ? invalid.rule() : "notApplicable";
            String code=failure instanceof AiException ai ? ai.error().name() : failure instanceof com.storystylist.backend.storage.StorageException stored ? stored.code() : "REQUEST_FAILED";
            log.info("Story recommendation failed: requestId={}, stage={}, rule={}, errorCode={}, durationMs={}",
                    requestId,stage,rule,code,(System.nanoTime()-overallStart)/1_000_000);
            throw failure;
        } finally {
            if(previous==null) org.slf4j.MDC.remove("storyRequestId"); else org.slf4j.MDC.put("storyRequestId",previous);
        }
    }
}
