package com.storystylist.backend.render;
import com.storystylist.backend.render.model.*;
import com.storystylist.backend.render.service.*;
import com.storystylist.backend.render.validation.*;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class RenderSpecificationCompilerTests {
    private final RecommendationValidationService plans=new RecommendationValidationService();
    private final RenderSpecificationValidator validator=new RenderSpecificationValidator(TextLayoutProperties.defaults());
    private final RenderSpecificationCompiler compiler=new RenderSpecificationCompiler(plans,validator);
    private List<PhotoAssetMetadata> assets(int count) {
        return java.util.stream.IntStream.rangeClosed(1,count).mapToObj(i->new PhotoAssetMetadata("photo-"+i,
                String.format("%064x",i),4000,2000,"image/jpeg",4000,2000)).toList();
    }
    private StoryRecommendation recommendation(int count,boolean caption,int rotation) {
        var ids=java.util.stream.IntStream.rangeClosed(1,count).mapToObj(i->"photo-"+i).toList();
        var images=new ArrayList<ImageLayer>();
        for(int i=0;i<count;i++) images.add(new ImageLayer(ids.get(i),i,(i%2)*.5,.1+(i/2)*.3,.5,.3,
                new ImageCrop(.1,.1,.8,.8),rotation,new ImageAdjustments(.2,1.1,.9),ImageFitMode.COVER,new FocalPoint(.7,.5)));
        var texts=caption?List.of(new TextLayer("A quiet afternoon",count,.1,.75,.8,.1,FontId.SANS_SERIF,36,"#FFFFFF",TextAlignment.CENTER,0)):List.<TextLayer>of();
        var summary=new StorySummary("Quiet light",ids,"Keep the moment simple.","The photographs share warm light.","A minimal arrangement.",
                new CaptionSuggestion(caption,caption?"A quiet afternoon":""),"The colors work together.");
        var raw=new StoryAiResult(List.of(new StoryDraft(summary,new StoryRenderPlan("story-render-plan-v3",new StoryCanvas(1080,1920),
                new StoryBackground(BackgroundType.SOLID,"#102030"),StoryLayoutType.values()[count-1],new StoryLayers(images,texts),new RenderingOptions(OutputFormat.JPEG,90)))));
        var draft=RenderPlanNormalizer.normalize(raw,assets(count),plans).recommendations().getFirst();
        return new StoryRecommendation("fixture-recommendation",draft.summary(),draft.renderPlan());
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4}) void compilesLayoutsAndSelectedManifestWithoutMutation(int count) {
        var input=recommendation(count,false,0);var source=assets(count+1);
        String before=com.storystylist.backend.ai.AiTestData.JSON.writeValueAsString(input);
        var result=compiler.compile(input,source);
        assertThat(result.canvas()).isEqualTo(new StoryCanvas(1080,1920));
        assertThat(result.output()).isEqualTo(RenderOutputOptions.png());
        assertThat(result.background().color()).isEqualTo("#102030");
        assertThat(result.assets()).containsExactlyElementsOf(source.subList(0,count));
        assertThat(result.layers()).hasSize(count);
        for(int i=0;i<count;i++) {
            var layer=(CompiledImageLayer)result.layers().get(i);
            assertThat(layer.order()).isEqualTo(i);assertThat(layer.photoId()).isEqualTo("photo-"+(i+1));
            assertThat(layer.destination()).isEqualTo(new PixelRectangle((i%2)*540,192+(i/2)*576,540,576));
            assertThat(layer.sourceCrop().width()/layer.sourceCrop().height()).isCloseTo(540./576,within(1e-9));
            assertThat(layer.adjustments()).isEqualTo(new ImageAdjustments(.2,1.1,.9));
        }
        assertThat(compiler.compile(input,source)).isEqualTo(result);
        assertThat(com.storystylist.backend.ai.AiTestData.JSON.writeValueAsString(input)).isEqualTo(before);
        assertThatThrownBy(()->result.layers().clear()).isInstanceOf(UnsupportedOperationException.class);
        // Compiler dependencies have no AI/provider boundary and generate no random fields.
        assertThat(Arrays.stream(RenderSpecificationCompiler.class.getDeclaredFields()).map(f->f.getType().getName()))
                .noneMatch(n->n.contains("VisionAiClient") || n.contains("genai") || n.contains("openai"));
    }
    @ParameterizedTest @ValueSource(ints={0,90,180,270}) void orientedAndRotatedCropUsesOriginalPixels(int rotation) {
        var input=recommendation(1,false,rotation);
        var a=new PhotoAssetMetadata("photo-1","1".repeat(64),2000,4000,"image/jpeg",4000,2000);
        var result=compiler.compile(input,List.of(a));
        var image=(CompiledImageLayer)result.layers().getFirst();var crop=input.renderPlan().layers().images().getFirst().crop();
        double w=(rotation==90||rotation==270)?2000:4000,h=(rotation==90||rotation==270)?4000:2000;
        assertThat(image.sourceCrop().x()).isCloseTo(crop.x()*w,within(1e-7));
        assertThat(image.sourceCrop().height()).isCloseTo(crop.height()*h,within(1e-7));
        assertThat(image.rotation()).isEqualTo(rotation); assertThat(result.assets().getFirst().sha256()).isEqualTo(a.sha256());
    }
    @Test void retainsResolvedTextAndPixelOffsetConvention() {
        var input=recommendation(2,true,0);var result=compiler.compile(input,assets(2));
        var original=input.renderPlan().layers().texts().getFirst();var compiled=(CompiledTextLayer)result.layers().getLast();
        assertThat(compiled.box()).isEqualTo(new TextPixelBox(108,1440,864,192));
        assertThat(compiled.layout()).isEqualTo(original.resolvedLayout());
        assertThat(compiled.fontSize()).isEqualTo(original.fontSize());assertThat(compiled.text()).isEqualTo(original.text());
        assertThat(compiled.box().y()+compiled.layout().lines().getFirst().baseline()).isBetween(1440.,1632.);
        validator.validate(result);
        var json=com.storystylist.backend.ai.AiTestData.JSON.writeValueAsString(result);
        assertThat(json).contains("compiled-story-v1","PNG","image/png","IMAGE","TEXT").doesNotContain("base64","MultipartFile");
    }
    @Test void roundSharedEdgesWithoutGapsAndRejectCollapsedRegions() {
        var a=RenderSpecificationCompiler.rectangle(0,0,1./3,1);
        var b=RenderSpecificationCompiler.rectangle(1./3,0,1./3,1);
        var c=RenderSpecificationCompiler.rectangle(2./3,0,1./3,1);
        assertThat(a.x()+a.width()).isEqualTo(b.x());assertThat(b.x()+b.width()).isEqualTo(c.x());
        assertThat(c.x()+c.width()).isEqualTo(1080);
        assertThatThrownBy(()->RenderSpecificationCompiler.rectangle(0,0,1e-8,1)).isInstanceOf(InvalidRenderSpecificationException.class);
        assertThatThrownBy(()->RenderSpecificationCompiler.rectangle(Double.NaN,0,1,1)).isInstanceOf(InvalidRenderSpecificationException.class);
    }
    @Test void rejectsMissingDuplicateAndInconsistentAssets() {
        var input=recommendation(1,false,0);var a=assets(1).getFirst();
        for(var invalid:List.of(List.<PhotoAssetMetadata>of(),List.of(a,a),
                List.of(new PhotoAssetMetadata("photo-1","bad",4000,2000,"image/jpeg",4000,2000)),
                List.of(new PhotoAssetMetadata("photo-1",a.sha256(),4000,2000,"image/jpeg",100,100)),
                List.of(new PhotoAssetMetadata("unknown",a.sha256(),4000,2000,"image/jpeg",4000,2000))))
            assertThatThrownBy(()->compiler.compile(input,invalid)).isInstanceOf(InvalidRenderSpecificationException.class);
    }
    @Test void rejectsUnsupportedLegacyOutputAndUnnormalizedPlans() {
        var r=recommendation(1,false,0);var p=r.renderPlan();
        for(var options:Arrays.asList(null,new RenderingOptions(OutputFormat.JPEG,80))) {
            var invalid=new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),p.layoutType(),p.layers(),options);
            assertThatThrownBy(()->compiler.compile(new StoryRecommendation(r.recommendationId(),r.summary(),invalid),assets(1)))
                    .isInstanceOf(InvalidRenderSpecificationException.class);
        }
        var image=p.layers().images().getFirst();
        var bad=new ImageLayer(image.photoId(),0,image.x(),image.y(),image.width(),image.height(),new ImageCrop(0.,0.,1.,1.),0,image.adjustments());
        var invalid=new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),p.layoutType(),new StoryLayers(List.of(bad),List.of()),p.renderingOptions());
        assertThatThrownBy(()->compiler.compile(new StoryRecommendation(r.recommendationId(),r.summary(),invalid),assets(1)))
                .isInstanceOf(InvalidRenderSpecificationException.class);
    }
    @Test void compiledValidatorRejectsCorruptedExecutableFields() {
        var valid=compiler.compile(recommendation(1,false,0),assets(1));var image=(CompiledImageLayer)valid.layers().getFirst();
        for(var invalid:List.of(new CompiledImageLayer(1,image.photoId(),image.destination(),image.sourceCrop(),0,image.fitMode(),image.adjustments()),
                new CompiledImageLayer(0,"unknown",image.destination(),image.sourceCrop(),0,image.fitMode(),image.adjustments()),
                new CompiledImageLayer(0,image.photoId(),new PixelRectangle(1080,0,10,10),image.sourceCrop(),0,image.fitMode(),image.adjustments()),
                new CompiledImageLayer(0,image.photoId(),image.destination(),new PixelCrop(0,0,Double.NaN,10),0,image.fitMode(),image.adjustments()),
                new CompiledImageLayer(0,image.photoId(),image.destination(),image.sourceCrop(),45,image.fitMode(),image.adjustments()))) {
            assertThatThrownBy(()->validator.validate(new CompiledStorySpecification(valid.specificationVersion(),valid.recommendationId(),valid.canvas(),valid.output(),valid.background(),List.of(invalid),valid.assets())))
                    .isInstanceOf(InvalidRenderSpecificationException.class);
        }
        assertThatThrownBy(()->validator.validate(new CompiledStorySpecification(valid.specificationVersion(),valid.recommendationId(),valid.canvas(),
                new RenderOutputOptions(RenderOutputOptions.Format.PNG,"image/jpeg",RenderOutputOptions.ColorModel.RGB),valid.background(),valid.layers(),valid.assets())))
                .isInstanceOf(InvalidRenderSpecificationException.class);
    }
    @ParameterizedTest @ValueSource(doubles={.123456789,.314159265,.333333333,.71231231})
    void preservesFractionalTextBoxesAndLayouts(double width) {
        var r=recommendation(1,true,0);var p=r.renderPlan();var t=p.layers().texts().getFirst();
        var raw=new TextLayer(t.text(),t.order(),.1,.734567891,width,.123456789,t.font(),t.fontSize(),t.color(),t.alignment(),0);
        var plan=new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),p.layoutType(),
                new StoryLayers(p.layers().images(),List.of(raw)),p.renderingOptions());
        var normalized=RenderPlanNormalizer.normalize(new StoryAiResult(List.of(new StoryDraft(r.summary(),plan))),assets(1),plans).recommendations().getFirst();
        var compiled=compiler.compile(new StoryRecommendation(r.recommendationId(),normalized.summary(),normalized.renderPlan()),assets(1));
        assertThat(((CompiledTextLayer)compiled.layers().getLast()).layout()).isEqualTo(normalized.renderPlan().layers().texts().getFirst().resolvedLayout());
    }
    @Test void reconcilesOnlyPixelRoundingInsideFinalCrop() {
        var r=recommendation(1,false,0);var p=r.renderPlan();var old=p.layers().images().getFirst();
        var raw=new ImageLayer(old.photoId(),0,.12345,.12345,.43217,.31719,old.crop(),0,old.adjustments(),ImageFitMode.COVER,new FocalPoint(.7,.5));
        var plan=new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),p.layoutType(),new StoryLayers(List.of(raw),List.of()),p.renderingOptions());
        var normalized=RenderPlanNormalizer.normalize(new StoryAiResult(List.of(new StoryDraft(r.summary(),plan))),assets(1),plans).recommendations().getFirst();
        var preferred=normalized.renderPlan().layers().images().getFirst().crop();
        var compiled=compiler.compile(new StoryRecommendation(r.recommendationId(),normalized.summary(),normalized.renderPlan()),assets(1));
        var i=(CompiledImageLayer)compiled.layers().getFirst();var crop=i.sourceCrop();
        assertThat(crop.x()).isGreaterThanOrEqualTo(preferred.x()*4000-1e-7);
        assertThat(crop.y()).isGreaterThanOrEqualTo(preferred.y()*2000-1e-7);
        assertThat(crop.x()+crop.width()).isLessThanOrEqualTo((preferred.x()+preferred.width())*4000+1e-7);
        assertThat(crop.y()+crop.height()).isLessThanOrEqualTo((preferred.y()+preferred.height())*2000+1e-7);
        assertThat(crop.width()/crop.height()).isCloseTo((double)i.destination().width()/i.destination().height(),within(1e-9));
    }
    @Test void rejectsUnsupportedTextAndDuplicateGlobalOrder() {
        var valid=compiler.compile(recommendation(1,true,0),assets(1));var t=(CompiledTextLayer)valid.layers().getLast();
        for(var corrupt:List.of(new CompiledTextLayer(0,t.text(),t.box(),t.font(),t.fontSize(),t.color(),t.alignment(),0,t.layout()),
                new CompiledTextLayer(1,t.text(),t.box(),null,t.fontSize(),t.color(),t.alignment(),0,t.layout()),
                new CompiledTextLayer(1,t.text(),t.box(),t.font(),t.fontSize(),t.color(),t.alignment(),90,t.layout())))
            assertThatThrownBy(()->validator.validate(new CompiledStorySpecification(valid.specificationVersion(),valid.recommendationId(),valid.canvas(),valid.output(),valid.background(),
                    List.of(valid.layers().getFirst(),corrupt),valid.assets()))).isInstanceOf(InvalidRenderSpecificationException.class);
    }
    @Test void usesLayerOrderRatherThanUploadOrder() {
        var r=recommendation(2,false,0);var p=r.renderPlan();var s=r.summary();
        var images=new ArrayList<ImageLayer>();
        for(int i=0;i<2;i++) {
            var old=p.layers().images().get(i);
            images.add(new ImageLayer("photo-"+(2-i),i,old.x(),old.y(),old.width(),old.height(),old.crop(),old.rotation(),old.adjustments(),old.fitMode(),old.focalPoint()));
        }
        var summary=new StorySummary(s.title(),List.of("photo-2","photo-1"),s.concept(),s.selectionReason(),s.layoutSummary(),s.caption(),s.whyItWorks());
        var plan=new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),p.layoutType(),new StoryLayers(images,List.of()),p.renderingOptions());
        var compiled=compiler.compile(new StoryRecommendation(r.recommendationId(),summary,plan),assets(3));
        assertThat(compiled.assets()).extracting(PhotoAssetMetadata::photoId).containsExactly("photo-2","photo-1");
        assertThat(((CompiledImageLayer)compiled.layers().getFirst()).photoId()).isEqualTo("photo-2");
    }
}
