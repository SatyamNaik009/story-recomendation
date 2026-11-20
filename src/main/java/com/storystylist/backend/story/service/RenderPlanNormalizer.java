package com.storystylist.backend.story.service;
import com.storystylist.backend.story.dto.*;
import java.util.*;
public final class RenderPlanNormalizer {
    private RenderPlanNormalizer() {}
    public static StoryAiResult normalize(StoryAiResult input,List<PhotoAssetMetadata> assets,RecommendationValidationService validation) {
        validation.validatePreferred(input,assets);
        var covered=CoverCropResolver.resolve(input,assets);
        var failures=new ArrayList<String>();
        var drafts=new ArrayList<StoryDraft>();
        for(int i=0;i<covered.recommendations().size();i++) {
            var d=covered.recommendations().get(i); var p=d.renderPlan(); var texts=new ArrayList<TextLayer>();
            for(var text:p.layers().texts()) {
                try { texts.add(TextLayoutResolver.resolve(text,validation.textSettings())); }
                catch(InvalidRecommendationException e) { failures.add("recommendations["+i+"]."+e.rule()); }
            }
            drafts.add(new StoryDraft(d.summary(),new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),p.layoutType(),
                    new StoryLayers(p.layers().images(),List.copyOf(texts)),p.renderingOptions())));
        }
        if(!failures.isEmpty()) throw new InvalidRecommendationException(String.join(",",failures));
        var result=new StoryAiResult(List.copyOf(drafts));
        validation.validate(result,assets);
        return result;
    }
}
