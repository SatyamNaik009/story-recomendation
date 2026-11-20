package com.storystylist.backend.story;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.*;
import com.storystylist.backend.story.ai.*;
import com.storystylist.backend.ai.validation.VisionResponseCodec;
import com.storystylist.backend.ai.exception.AiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.storystylist.backend.ai.AiTestData.JSON;
public class StoryTests {
    public static StoryDraft draft(String id) {
        return new StoryDraft(new StorySummary("A quiet moment",List.of(id),"Let the photograph lead.",
                "A clear composition.","One photograph with breathing room.",new CaptionSuggestion(false,""),"Simple and focused."),
                new StoryRenderPlan("story-render-plan-v3",new StoryCanvas(1080,1920),new StoryBackground(BackgroundType.SOLID,"#000000"),
                        StoryLayoutType.SINGLE,new StoryLayers(List.of(new ImageLayer(id,0,0.,.2,1.,.28125,
                        new ImageCrop(0.,0.,1.,1.),0,new ImageAdjustments(0.,1.,1.))),List.of()),new RenderingOptions(OutputFormat.JPEG,90)));
    }
    public static String json() { return JSON.writeValueAsString(new StoryAiResult(List.of(draft("photo-1")))); }
    private List<PhotoAssetMetadata> assets() { return List.of(new PhotoAssetMetadata("photo-1","hash",20,10,"image/png",20,10),
            new PhotoAssetMetadata("photo-2","hash2",20,10,"image/png",20,10),new PhotoAssetMetadata("photo-3","hash3",20,10,"image/png",20,10)); }
    @ParameterizedTest @ValueSource(ints={1,2,3}) void acceptsDistinctRecommendations(int count) {
        var result=new StoryAiResult(java.util.stream.IntStream.rangeClosed(1,count).mapToObj(i->draft("photo-"+i)).toList());
        assertThat(new RecommendationValidationService().validate(result,assets())).hasSize(count);
    }
    @ParameterizedTest @ValueSource(strings={
        "\"width\":1080|\"width\":1000", "story-render-plan-v3|unknown-v2", "photo-1|unknown",
        "\"x\":0.0|\"x\":-0.1",
        "\"height\":0.28125|\"height\":0.3", "\"rotation\":0|\"rotation\":45",
        "\"contrast\":1.0|\"contrast\":2.0", "\"exposure\":0.0|\"exposure\":2.0",
        "\"saturation\":1.0|\"saturation\":-1.0", "SINGLE|SPLIT_TWO", "#000000|transparent",
        "\"include\":false|\"include\":true", "\"order\":0|\"order\":2",
        "\"title\":\"A quiet moment\"|\"title\":\"\"",
        "\"selectedPhotoIds\":[\"photo-1\"]|\"selectedPhotoIds\":[\"photo-1\",\"photo-1\"]",
        "\"format\":\"JPEG\"|\"format\":\"GIF\"",
        "\"exposure\":0.0|\"effect\":\"glow\",\"exposure\":0.0",
        "\"crop\":{|\"crop\":{\"unsupported\":true,",
        "\"schemaVersion\":\"story-render-plan-v3\",|"
    }) void rejectsInvalidOrUnsupportedPlans(String mutation) {
        var pair=mutation.split("\\|",-1);
        assertThat(json()).contains(pair[0]);
        var changed=json().replace(pair[0],pair[1]);
        assertThatThrownBy(()->new RecommendationValidationService().validate(
                new VisionResponseCodec().parse(changed,StoryAiResult.class),assets())).isInstanceOf(AiException.class);
    }
    @Test void rejectsEmptyDuplicateAndTooManyRecommendations() {
        for(var list:List.of(List.<StoryDraft>of(),List.of(draft("photo-1"),draft("photo-1")),
                List.of(draft("photo-1"),draft("photo-2"),draft("photo-3"),draft("photo-1")))) {
            assertThatThrownBy(()->new RecommendationValidationService().validate(new StoryAiResult(list),assets())).isInstanceOf(AiException.class);
        }
    }
    @Test void closedSchemaRequiresEveryField() {
        assertThat(StorySchema.schema().get("additionalProperties")).isEqualTo(false);
        assertThat(JSON.writeValueAsString(StorySchema.schema())).contains("SINGLE","SANS_SERIF","renderPlan","required");
    }
    @Test void acceptsCaptionAndQuarterTurnWithMatchingGeometry() {
        var d=draft("photo-1"); var s=d.summary(); var p=d.renderPlan();
        var summary=new StorySummary(s.title(),s.selectedPhotoIds(),s.concept(),s.selectionReason(),s.layoutSummary(),new CaptionSuggestion(true,"Today"),s.whyItWorks());
        var image=new ImageLayer("photo-1",0,.1,.2,.4,.45,new ImageCrop(0.,0.,1.,1.),90,new ImageAdjustments(0.,1.,1.));
        var caption=new TextLayer("Today",1,.1,.7,.8,.1,FontId.SANS_SERIF,32,"#FFFFFF",TextAlignment.CENTER,0);
        var plan=new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),p.layoutType(),new StoryLayers(List.of(image),List.of(caption)),p.renderingOptions());
        assertThat(RenderPlanNormalizer.normalize(new StoryAiResult(List.of(new StoryDraft(summary,plan))),assets(),new RecommendationValidationService()).recommendations()).hasSize(1);
    }
    @Test void originalExifDimensionsAndHashArePreserved() throws Exception {
        var bytes=com.storystylist.backend.photo.ProcessingTestImages.orientedJpeg(40,20,6);
        var upload=new org.springframework.mock.web.MockMultipartFile("photos","image.jpg","image/jpeg",bytes);
        var asset=com.storystylist.backend.photo.service.PhotoAssetReader.read(upload,"photo-1");
        assertThat(asset.originalWidth()).isEqualTo(40); assertThat(asset.originalHeight()).isEqualTo(20);
        assertThat(asset.orientedWidth()).isEqualTo(20); assertThat(asset.orientedHeight()).isEqualTo(40);
        assertThat(asset.sha256()).isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    @ParameterizedTest @ValueSource(ints={2,3,4}) void supportsEveryCollageAndRequiresEverySelectedLayer(int count) {
        var base=draft("photo-1"); var s=base.summary(); var p=base.renderPlan();
        var ids=java.util.stream.IntStream.rangeClosed(1,count).mapToObj(i->"photo-"+i).toList();
        var assets=ids.stream().map(id->new PhotoAssetMetadata(id,"hash",20,10,"image/png",20,10)).toList();
        var images=java.util.stream.IntStream.range(0,count).mapToObj(i->new ImageLayer(ids.get(i),i,
                (i%2)*.5,.2+(i/2)*.2,.5,.140625,new ImageCrop(0.,0.,1.,1.),0,new ImageAdjustments(0.,1.,1.))).toList();
        var summary=new StorySummary(s.title(),ids,s.concept(),s.selectionReason(),s.layoutSummary(),s.caption(),s.whyItWorks());
        var plan=new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),StoryLayoutType.values()[count-1],
                new StoryLayers(images,List.of()),p.renderingOptions());
        var validator=new RecommendationValidationService();
        assertThat(validator.validate(new StoryAiResult(List.of(new StoryDraft(summary,plan))),assets)).hasSize(1);
        var incomplete=new StoryRenderPlan(plan.schemaVersion(),plan.canvas(),plan.background(),plan.layoutType(),
                new StoryLayers(images.subList(1,images.size()),List.of()),plan.renderingOptions());
        assertThatThrownBy(()->validator.validate(new StoryAiResult(List.of(new StoryDraft(summary,incomplete))),assets)).isInstanceOf(AiException.class);
    }
    @ParameterizedTest @ValueSource(doubles={Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
    void rejectsNonFiniteCoordinates(double x) {
        var d=draft("photo-1"); var p=d.renderPlan(); var image=p.layers().images().getFirst();
        var invalid=new ImageLayer(image.photoId(),image.order(),x,image.y(),image.width(),image.height(),image.crop(),image.rotation(),image.adjustments());
        var plan=new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),p.layoutType(),new StoryLayers(List.of(invalid),List.of()),p.renderingOptions());
        assertThatThrownBy(()->new RecommendationValidationService().validate(new StoryAiResult(List.of(new StoryDraft(d.summary(),plan))),assets()))
                .isInstanceOf(AiException.class);
    }
}
