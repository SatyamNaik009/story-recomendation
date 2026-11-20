package com.storystylist.backend.story;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.*;
import com.storystylist.backend.ai.exception.AiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.awt.Font;
import java.awt.font.TextLayout;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class TextLayoutResolverTests {
    private final TextLayoutProperties limits=TextLayoutProperties.defaults();
    private TextLayer text(String content,int size,double width,double height) {
        return new TextLayer(content,1,.1,.2,width,height,FontId.SANS_SERIF,size,"#FFFFFF",TextAlignment.CENTER,0);
    }
    @ParameterizedTest @ValueSource(strings={"Hi", "Hello, WORLD!", "A quiet afternoon", "Café déjà vu", "First line\nSecond line", "A\n\nB", "A\r\nB"})
    void measuresPreservesTextAndIsDeterministic(String caption) {
        var raw=text(caption,48,.8,.3); var resolved=TextLayoutResolver.resolve(raw,limits);
        assertThat(resolved.text()).isEqualTo(caption);
        assertThat(resolved.fontSize()).isEqualTo(48);
        assertThat(TextLayoutResolver.resolve(raw,limits)).isEqualTo(resolved);
        assertThat(TextLayoutResolver.resolve(resolved,limits)).isEqualTo(resolved);
        TextLayoutResolver.validateFinal(resolved,limits);
        for(var line:resolved.resolvedLayout().lines()) {
            assertThat(line.xOffset()).isGreaterThanOrEqualTo(4);
            assertThat(line.baseline()).isLessThan(resolved.height()*1920-4);
            assertThat(line.width()).isLessThanOrEqualTo(resolved.width()*1080-8+1e-7);
        }
    }
    @Test void measuresVariableWidthGlyphsRatherThanCharacterCount() {
        var small=TextLayoutResolver.resolve(text("iiiiiiiiii",48,.2,.1),limits);
        var wide=TextLayoutResolver.resolve(text("WWWWWWWWWW",48,.8,.1),limits);
        assertThat(small.fontSize()).isEqualTo(48);
        assertThat(small.resolvedLayout().lines().getFirst().width()).isLessThan(wide.resolvedLayout().lines().getFirst().width());
        var measured=new TextLayout("iiiiiiiiii",new Font(Font.SANS_SERIF,Font.PLAIN,48),TextLayoutResolver.CONTEXT);
        assertThat(small.resolvedLayout().lines().getFirst().width()).isCloseTo(measured.getAdvance(),within(1e-5));
        assertThat(small.resolvedLayout().lines().getFirst().baseline()).isGreaterThanOrEqualTo(4+measured.getAscent());
    }
    @Test void wrapsThenShrinksWithoutChangingBoxOrAlignment() {
        String caption="A beautiful afternoon with friends and warm light";
        var tall=TextLayoutResolver.resolve(text(caption,72,.3,.5),limits);
        assertThat(tall.resolvedLayout().lines()).hasSizeGreaterThan(1);
        var shortBox=text(caption,72,.3,.1);
        var fitted=TextLayoutResolver.resolve(shortBox,limits);
        assertThat(fitted.fontSize()).isLessThan(72).isGreaterThanOrEqualTo(24);
        assertThat(fitted.width()).isEqualTo(shortBox.width()); assertThat(fitted.height()).isEqualTo(shortBox.height());
        assertThat(fitted.alignment()).isEqualTo(shortBox.alignment());
        TextLayoutResolver.validateFinal(fitted,limits);
    }
    @ParameterizedTest @ValueSource(ints={24,72}) void honorsSupportedBoundarySizes(int size) {
        assertThat(TextLayoutResolver.resolve(text("Hi",size,.8,.2),limits).fontSize()).isEqualTo(size);
    }
    @Test void rejectsImpossibleTextAndUnsupportedGlyphs() {
        for(var raw:List.of(text("Impossible",24,.001,.1),text("Impossible",24,.8,.001),text("\uDBFF\uDFFF",24,.8,.2)))
            assertThatThrownBy(()->TextLayoutResolver.resolve(raw,limits)).isInstanceOf(AiException.class);
        assertThatThrownBy(()->TextLayoutResolver.resolve(new TextLayer("Hi",1,.1,.2,.8,.2,null,32,"#FFFFFF",TextAlignment.LEFT,0),limits))
                .isInstanceOf(AiException.class);
    }
    @Test void customLimitsAndActualFinalLayoutAreEnforced() {
        var custom=new TextLayoutProperties(28,90);
        assertThat(TextLayoutResolver.resolve(text("Hi",90,.8,.2),custom).fontSize()).isEqualTo(90);
        assertThatThrownBy(()->TextLayoutResolver.resolve(text("Hi",24,.8,.2),custom)).isInstanceOf(AiException.class);
        assertThatThrownBy(()->TextLayoutResolver.validateFinal(text("Hi",32,.8,.2),limits)).isInstanceOf(AiException.class);
        assertThatThrownBy(()->new TextLayoutProperties(90,28)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void coverAndTextNormalizeTogetherIdempotently() {
        var base=StoryTests.draft("photo-1"); var p=base.renderPlan(); var s=base.summary();
        var image=p.layers().images().getFirst();
        var mismatch=new ImageLayer(image.photoId(),0,0.,.1,1.,.7,image.crop(),0,image.adjustments());
        var caption=text("iiiiiiiiii",48,.2,.1);
        var plan=new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),p.layoutType(),new StoryLayers(List.of(mismatch),List.of(caption)),p.renderingOptions());
        var summary=new StorySummary(s.title(),s.selectedPhotoIds(),s.concept(),s.selectionReason(),s.layoutSummary(),new CaptionSuggestion(true,caption.text()),s.whyItWorks());
        var raw=new StoryAiResult(List.of(new StoryDraft(summary,plan)));
        var assets=List.of(new PhotoAssetMetadata("photo-1","hash",20,10,"image/jpeg",20,10));
        var validation=new RecommendationValidationService();
        var result=RenderPlanNormalizer.normalize(raw,assets,validation);
        assertThat(RenderPlanNormalizer.normalize(result,assets,validation)).isEqualTo(result);
        assertThat(result.recommendations().getFirst().renderPlan().layers().texts().getFirst().fontSize()).isEqualTo(48);
        assertThat(result.recommendations().getFirst().renderPlan().layers().images().getFirst().crop()).isNotEqualTo(image.crop());
    }
}
