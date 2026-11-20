package com.storystylist.backend.render.service;
import com.storystylist.backend.ai.validation.VisionResponseCodec;
import com.storystylist.backend.render.validation.InvalidRenderSpecificationException;
import com.storystylist.backend.story.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
@Service
public class StoryRenderer {
    private final VisionResponseCodec codec;
    private final OriginalPhotoAssetResolver assets;
    private final RenderSpecificationCompiler compiler;
    private final Java2DStoryRenderer renderer;
    private final SinglePhotoLayoutRefiner refiner;
    public StoryRenderer(VisionResponseCodec codec,OriginalPhotoAssetResolver assets,RenderSpecificationCompiler compiler,Java2DStoryRenderer renderer,SinglePhotoLayoutRefiner refiner) {
        this.refiner=refiner;this.codec=codec;this.assets=assets;this.compiler=compiler;this.renderer=renderer;
    }
    public byte[] render(String planJson,String assetsJson,String recommendationId,List<MultipartFile> photos) {
        if(planJson==null || planJson.isBlank())throw RenderRequestException.invalid("RENDER_PLAN_MISSING","Supply renderPlan JSON from the recommendation response");
        if(assetsJson==null || assetsJson.isBlank())throw RenderRequestException.invalid("RENDER_ASSETS_MISSING","Supply the original assets metadata array");
        if(recommendationId!=null && recommendationId.length()>128)throw RenderRequestException.invalid("RENDER_ID_INVALID","Recommendation identifier is too long");
        StoryRenderPlan plan;List<PhotoAssetMetadata> metadata;
        try {plan=codec.parse(planJson,StoryRenderPlan.class);metadata=Arrays.asList(codec.parse(assetsJson,PhotoAssetMetadata[].class));}
        catch(RuntimeException e){throw RenderRequestException.invalid("RENDER_JSON_INVALID","Render plan or asset metadata JSON is invalid");}
        if(plan==null || plan.layoutType()==null)throw RenderRequestException.invalid("RENDER_PLAN_INVALID","Render plan is invalid");
        if(plan.layoutType()!=StoryLayoutType.SINGLE)throw new RenderRequestException("RENDER_LAYOUT_UNSUPPORTED","Only SINGLE layouts can be rendered in this milestone",422);
        var originals=assets.verify(photos,metadata);
        return renderVerified(recommendationId,plan,metadata,originals);
    }
    /** Internal entry point for hash-verified originals with their unchanged stable IDs. */
    public byte[] renderVerified(String recommendationId,StoryRenderPlan plan,List<PhotoAssetMetadata> metadata,Map<String,MultipartFile> originals) {
        if(plan.layoutType()!=StoryLayoutType.SINGLE)throw new RenderRequestException("RENDER_LAYOUT_UNSUPPORTED","Only SINGLE layouts can be rendered in this milestone",422);
        try {
            var spec=compiler.compilePlan(recommendationId==null || recommendationId.isBlank()?"local-render":recommendationId,plan,metadata);
            if(refiner.enabled()) {
                var refined=refiner.refine(plan,metadata);
                spec=compiler.compilePlan(spec.recommendationId(),refined,metadata);
            }
            return renderer.render(spec,originals,refiner.enabled());
        } catch(InvalidRenderSpecificationException e){throw RenderRequestException.invalid("RENDER_PLAN_INVALID","Render plan is invalid or is not finalized for this runtime");}
    }
}
