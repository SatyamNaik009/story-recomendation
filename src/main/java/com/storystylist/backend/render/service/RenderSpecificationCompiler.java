package com.storystylist.backend.render.service;
import com.storystylist.backend.render.model.*;
import com.storystylist.backend.render.validation.*;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.storystylist.backend.render.validation.RenderSpecificationValidator.require;
@Service
public class RenderSpecificationCompiler {
    private final RecommendationValidationService planValidator;
    private final RenderSpecificationValidator specificationValidator;
    public RenderSpecificationCompiler(RecommendationValidationService planValidator,RenderSpecificationValidator specificationValidator) {
        this.planValidator=planValidator;this.specificationValidator=specificationValidator;
    }
    /** Structural adapter for render requests, which intentionally do not resend user-facing summaries. */
    public CompiledStorySpecification compilePlan(String id,StoryRenderPlan plan,List<PhotoAssetMetadata> assets) {
        try {
            require(plan!=null && plan.layers()!=null,"plan.required");
            var ids=plan.layers().images().stream().map(ImageLayer::photoId).toList();
            var texts=plan.layers().texts();
            var caption=new CaptionSuggestion(!texts.isEmpty(),texts.isEmpty()?"":texts.getFirst().text());
            // Only the existing compiler's envelope validator consumes these labels; no creative output is generated.
            var summary=new StorySummary("Supplied plan",ids,"Supplied plan","Supplied plan","Supplied plan",caption,"Supplied plan");
            return compile(new StoryRecommendation(id,summary,plan),assets);
        } catch(InvalidRenderSpecificationException e) { throw e; }
        catch(RuntimeException e) { throw new InvalidRenderSpecificationException("plan.invalidStructure"); }
    }
    /** Consumes a selected, already finalized v3 recommendation. Never normalizes creative input or calls AI. */
    public CompiledStorySpecification compile(StoryRecommendation recommendation,List<PhotoAssetMetadata> assets) {
        try {
            require(recommendation!=null && recommendation.recommendationId()!=null && !recommendation.recommendationId().isBlank(),"recommendation.id");
            require(assets!=null,"assets.required");
            Map<String,PhotoAssetMetadata> source=new HashMap<>();
            for(var a:assets) { RenderSpecificationValidator.validateAsset(a); require(source.put(a.photoId(),a)==null,"asset.duplicateId"); }
            var plan=recommendation.renderPlan();
            planValidator.validate(new StoryAiResult(List.of(new StoryDraft(recommendation.summary(),plan))),assets);
            // v3's only accepted output hint is JPEG/90, already checked above. Product output is explicitly PNG.
            var layers=new ArrayList<CompiledLayer>();var manifest=new ArrayList<PhotoAssetMetadata>();
            for(var image:plan.layers().images()) {
                var asset=source.get(image.photoId());manifest.add(asset);
                var dest=rectangle(image.x(),image.y(),image.width(),image.height());
                var rounded=new ImageLayer(image.photoId(),image.order(),dest.x()/1080.,dest.y()/1920.,dest.width()/1080.,dest.height()/1920.,
                        image.crop(),image.rotation(),image.adjustments(),image.fitMode(),image.focalPoint());
                // Only reconcile integer destination edge rounding; the final creative crop is the hard boundary.
                var crop=CoverCropResolver.resolve(rounded,asset);
                double w=asset.orientedWidth(),h=asset.orientedHeight();
                if(image.rotation()==90 || image.rotation()==270) {double temp=w;w=h;h=temp;}
                layers.add(new CompiledImageLayer(image.order(),image.photoId(),dest,new PixelCrop(crop.x()*w,crop.y()*h,crop.width()*w,crop.height()*h),
                        image.rotation(),image.fitMode(),image.adjustments()));
            }
            for(var text:plan.layers().texts()) layers.add(new CompiledTextLayer(text.order(),text.text(),
                    new TextPixelBox(text.x()*1080,text.y()*1920,text.width()*1080,text.height()*1920),text.font(),text.fontSize(),text.color(),
                    text.alignment(),text.rotation(),text.resolvedLayout()));
            layers.sort(Comparator.comparingInt(CompiledLayer::order));
            var compiled=new CompiledStorySpecification("compiled-story-v1",recommendation.recommendationId(),plan.canvas(),RenderOutputOptions.png(),
                    plan.background(),layers,manifest);
            specificationValidator.validate(compiled);
            return compiled;
        } catch(InvalidRenderSpecificationException e) { throw e; }
        catch(RuntimeException e) { throw new InvalidRenderSpecificationException("plan.invalidOrNotFinalized"); }
    }
    public static PixelRectangle rectangle(double x,double y,double width,double height) {
        require(Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(width)&&Double.isFinite(height)
                && x>=0 && y>=0 && width>0 && height>0 && x+width<=1.000000001 && y+height<=1.000000001,"destination.bounds");
        int left=(int)Math.round(x*1080),top=(int)Math.round(y*1920);
        int right=(int)Math.round((x+width)*1080),bottom=(int)Math.round((y+height)*1920);
        require(right>left && bottom>top,"destination.collapsedAfterRounding");
        return new PixelRectangle(left,top,right-left,bottom-top);
    }
}
