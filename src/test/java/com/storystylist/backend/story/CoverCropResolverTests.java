package com.storystylist.backend.story;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.*;
import com.storystylist.backend.ai.exception.AiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
class CoverCropResolverTests {
    private ImageLayer layer(ImageCrop crop,FocalPoint focal,int rotation,double w,double h) {
        return new ImageLayer("photo-1",0,0.,0.,w,h,crop,rotation,new ImageAdjustments(0.,1.,1.),ImageFitMode.COVER,focal);
    }
    private PhotoAssetMetadata asset(int w,int h) { return new PhotoAssetMetadata("photo-1","hash",w,h,"image/jpeg",w,h); }
    @ParameterizedTest @CsvSource({"4000,2000,1,1,0", "2000,4000,1,0.5625,0", "2000,2000,1,1,0",
            "1080,1920,1,1,0", "4000,2000,1,1,90", "4000,2000,1,1,270", "4000,2000,1,1,180"})
    void matchesPhysicalAspectRatiosWithoutStretching(int sw,int sh,double dw,double dh,int rotation) {
        var preferred=new ImageCrop(.1,.1,.8,.8);
        var input=layer(preferred,null,rotation,dw,dh);
        var source=asset(sw,sh);
        var crop=CoverCropResolver.resolve(input,source);
        double width=(rotation==90||rotation==270)?sh:sw, height=(rotation==90||rotation==270)?sw:sh;
        assertThat(crop.width()*width/(crop.height()*height)).isCloseTo(dw*1080/(dh*1920),within(1e-10));
        assertThat(crop.x()).isGreaterThanOrEqualTo(preferred.x());
        assertThat(crop.y()).isGreaterThanOrEqualTo(preferred.y());
        assertThat(crop.x()+crop.width()).isLessThanOrEqualTo(.9000000001);
        assertThat(crop.y()+crop.height()).isLessThanOrEqualTo(.9000000001);
        assertThat(crop.width()).isPositive().isLessThanOrEqualTo(preferred.width());
        assertThat(crop.height()).isPositive().isLessThanOrEqualTo(preferred.height());
        assertThat(CoverCropResolver.resolve(input,source)).isEqualTo(crop);
        assertThat(CoverCropResolver.resolve(layer(crop,null,rotation,dw,dh),source)).isEqualTo(crop);
    }
    @ParameterizedTest @CsvSource({"0,0.5", "1,0.5", "0.5,0", "0.5,1", "0.5,0.5"})
    void preservesFocalPointsAtEveryEdge(double x,double y) {
        var preferred=new ImageCrop(0.,0.,1.,1.);
        for(var source:java.util.List.of(asset(4000,2000),asset(2000,4000))) {
            var crop=CoverCropResolver.resolve(layer(preferred,new FocalPoint(x,y),0,1.,.5625),source);
            assertThat(x).isBetween(crop.x()-1e-12,crop.x()+crop.width()+1e-12);
            assertThat(y).isBetween(crop.y()-1e-12,crop.y()+crop.height()+1e-12);
        }
    }
    @Test void clampsFocalOutsidePreferredRegionWithoutExpandingIt() {
        var crop=CoverCropResolver.resolve(layer(new ImageCrop(.2,.2,.6,.6),new FocalPoint(1.,1.),0,1.,1.),asset(4000,2000));
        assertThat(crop.x()+crop.width()).isCloseTo(.8,within(1e-12));
        assertThat(crop.y()+crop.height()).isCloseTo(.8,within(1e-12));
        assertThat(crop.width()).isCloseTo(.16875,within(1e-12));
    }
    @Test void fullLandscapeToPortraitHasExpectedCenteredCrop() {
        var crop=CoverCropResolver.resolve(layer(new ImageCrop(0.,0.,1.,1.),null,0,1.,1.),asset(4000,2000));
        assertThat(crop.width()).isEqualTo(.28125);
        assertThat(crop.height()).isEqualTo(1.);
        assertThat(crop.x()).isEqualTo(.359375);
        assertThat(crop.y()).isZero();
    }
    @ParameterizedTest @ValueSource(doubles={0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
    void rejectsInvalidDimensions(double invalid) {
        var full=new ImageCrop(0.,0.,1.,1.);
        assertThatThrownBy(()->CoverCropResolver.resolve(layer(full,null,0,invalid,1.),asset(4000,2000))).isInstanceOf(AiException.class);
        assertThatThrownBy(()->CoverCropResolver.resolve(layer(new ImageCrop(0.,0.,1.,invalid),null,0,1.,1.),asset(4000,2000))).isInstanceOf(AiException.class);
    }
    @Test void rejectsInvalidBoundsRotationModeReferencesAndFocalPoint() {
        var full=new ImageCrop(0.,0.,1.,1.);
        for(var input:java.util.List.of(layer(new ImageCrop(-.1,0.,1.,1.),null,0,1.,1.),
                layer(new ImageCrop(.2,0.,1.,1.),null,0,1.,1.),layer(full,null,45,1.,1.),
                layer(full,new FocalPoint(Double.NaN,.5),0,1.,1.),layer(full,new FocalPoint(1.1,.5),0,1.,1.),
                layer(full,null,0,1.1,1.))) {
            assertThatThrownBy(()->CoverCropResolver.resolve(input,asset(100,200))).isInstanceOf(AiException.class);
        }
        var input=layer(full,null,0,1.,1.);
        assertThatThrownBy(()->CoverCropResolver.resolve(input,asset(0,200))).isInstanceOf(AiException.class);
        assertThatThrownBy(()->CoverCropResolver.resolve(input,null)).isInstanceOf(AiException.class);
        var unsupported=new ImageLayer(input.photoId(),0,0.,0.,1.,1.,full,0,input.adjustments(),null,null);
        assertThatThrownBy(()->CoverCropResolver.resolve(unsupported,asset(100,200))).isInstanceOf(AiException.class);
    }
    @Test void schemaAndParserRequireSupportedFitAndRejectLegacyVersion() {
        var codec=new com.storystylist.backend.ai.validation.VisionResponseCodec();
        var validator=new RecommendationValidationService();
        for(String json:java.util.List.of(StoryTests.json().replace("\"fitMode\":\"COVER\"","\"fitMode\":null"),
                StoryTests.json().replace("COVER","CONTAIN"),
                StoryTests.json().replace("story-render-plan-v3","story-render-plan-v1"))) {
            assertThatThrownBy(()->validator.validatePreferred(codec.parse(json,StoryAiResult.class),java.util.List.of(asset(20,10))))
                    .isInstanceOf(AiException.class);
        }
        var schema=com.storystylist.backend.ai.AiTestData.JSON.writeValueAsString(com.storystylist.backend.story.ai.StorySchema.schema());
        assertThat(schema).contains("COVER","focalPoint","anyOf").doesNotContain("CONTAIN");
        var parsed=codec.parse(StoryTests.json(),StoryAiResult.class);
        assertThat(parsed.recommendations().getFirst().renderPlan().layers().images().getFirst().focalPoint()).isNull();
    }
}
