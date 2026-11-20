package com.storystylist.backend.render;
import com.storystylist.backend.render.service.*;
import com.storystylist.backend.render.validation.*;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.*;
import com.storystylist.backend.photo.service.*;
import com.storystylist.backend.photo.config.PhotoValidationProperties;
import com.storystylist.backend.photo.ProcessingTestImages;
import com.storystylist.backend.ai.validation.VisionResponseCodec;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static com.storystylist.backend.ai.AiTestData.JSON;
public class StoryRenderTests {
    static final ImageAdjustments NEUTRAL=new ImageAdjustments(0.,1.,1.);
    static PhotoValidationService validation(){return new PhotoValidationService(new PhotoValidationProperties(10,10485760,300,25000000,10000,10000));}
    static StoryRenderer renderer() {
        var text=TextLayoutProperties.defaults();var specs=new RenderSpecificationValidator(text);
        return new StoryRenderer(new VisionResponseCodec(),new OriginalPhotoAssetResolver(validation()),
                new RenderSpecificationCompiler(new RecommendationValidationService(),specs),new Java2DStoryRenderer(specs,text,new StoryImageEncodingService()),new SinglePhotoLayoutRefiner(new SinglePhotoPresentationProperties(false),text));
    }
    public static MockMultipartFile image() throws Exception {
        var image=new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try {g.setColor(Color.RED);g.fillRect(0,0,40,40);g.setColor(Color.BLUE);g.fillRect(40,0,40,40);g.setColor(Color.GREEN);g.fillRect(0,0,8,8);
            var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return new MockMultipartFile("photos","source.png","image/png",output.toByteArray());
        } finally {g.dispose();image.flush();}
    }
    public static StoryRenderPlan plan(List<PhotoAssetMetadata> assets,String id,int rotation,boolean caption,ImageAdjustments edits) {
        var a=assets.stream().filter(asset->asset.photoId().equals(id)).findFirst().orElseThrow();
        double ratio=(double)a.orientedWidth()/a.orientedHeight();if(rotation==90||rotation==270)ratio=1/ratio;
        var image=new ImageLayer(id,0,.1,.1,.5,.5*1080/(ratio*1920),new ImageCrop(0.,0.,1.,1.),rotation,edits);
        var texts=caption?List.of(new TextLayer("Out with the crew",1,.1,.75,.8,.1,FontId.SANS_SERIF,40,"#FFFFFF",TextAlignment.CENTER,0)):List.<TextLayer>of();
        var summary=new StorySummary("A moment",List.of(id),"Keep this photograph.","A clear composition.","One photo.",new CaptionSuggestion(caption,caption?"Out with the crew":""),"Simple and natural.");
        var raw=new StoryAiResult(List.of(new StoryDraft(summary,new StoryRenderPlan("story-render-plan-v3",new StoryCanvas(1080,1920),
                new StoryBackground(BackgroundType.SOLID,"#102030"),StoryLayoutType.SINGLE,new StoryLayers(List.of(image),texts),new RenderingOptions(OutputFormat.JPEG,90)))));
        return RenderPlanNormalizer.normalize(raw,assets,new RecommendationValidationService()).recommendations().getFirst().renderPlan();
    }
    static List<PhotoAssetMetadata> assets(List<? extends MultipartFile> photos) {
        return java.util.stream.IntStream.range(0,photos.size()).mapToObj(i->PhotoAssetReader.read(photos.get(i),"photo-"+(i+1))).toList();
    }
    static byte[] render(StoryRenderPlan plan,List<PhotoAssetMetadata> assets,List<MultipartFile> photos) {
        return renderer().render(JSON.writeValueAsString(plan),JSON.writeValueAsString(assets),"test",photos);
    }
    static void near(int actual,Color expected) {
        var c=new Color(actual);assertThat(c.getRed()).isCloseTo(expected.getRed(),within(25));
        assertThat(c.getGreen()).isCloseTo(expected.getGreen(),within(25));assertThat(c.getBlue()).isCloseTo(expected.getBlue(),within(25));
    }
    @Test void pngHasCorrectPixelsBackgroundPlacementAndOriginalIntegrity() throws Exception {
        var file=image();byte[] before=file.getBytes().clone();var assets=assets(List.of(file));var plan=plan(assets,"photo-1",0,false,NEUTRAL);
        byte[] png=render(plan,assets,List.of(file));assertThat(png).startsWith((byte)137,(byte)80,(byte)78,(byte)71,(byte)13,(byte)10,(byte)26,(byte)10);
        var out=ImageIO.read(new ByteArrayInputStream(png));try {
            assertThat(out.getWidth()).isEqualTo(1080);assertThat(out.getHeight()).isEqualTo(1920);
            assertThat(out.getColorModel().hasAlpha()).isFalse();assertThat(out.getRGB(0,0)&0xffffff).isEqualTo(0x102030);
            near(out.getRGB(200,350),Color.RED);near(out.getRGB(550,350),Color.BLUE);near(out.getRGB(125,210),Color.GREEN);
            assertThat(out.getRGB(650,350)&0xffffff).isEqualTo(0x102030);
        } finally {out.flush();}
        assertThat(file.getBytes()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7,8}) void appliesExifExactlyOnce(int orientation) throws Exception {
        var file=new MockMultipartFile("photos","source.jpg","image/jpeg",ProcessingTestImages.orientedJpeg(80,40,orientation));
        var assets=assets(List.of(file));var plan=plan(assets,"photo-1",0,false,NEUTRAL);
        var out=ImageIO.read(new ByteArrayInputStream(render(plan,assets,List.of(file))));
        Color[][] expected={{Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW},{Color.GREEN,Color.RED,Color.YELLOW,Color.BLUE},
                {Color.YELLOW,Color.BLUE,Color.GREEN,Color.RED},{Color.BLUE,Color.YELLOW,Color.RED,Color.GREEN},
                {Color.RED,Color.BLUE,Color.GREEN,Color.YELLOW},{Color.BLUE,Color.RED,Color.YELLOW,Color.GREEN},
                {Color.YELLOW,Color.GREEN,Color.BLUE,Color.RED},{Color.GREEN,Color.YELLOW,Color.RED,Color.BLUE}};
        int h=(int)Math.round(plan.layers().images().getFirst().height()*1920);
        try {for(int i=0;i<4;i++)near(out.getRGB(108+(i%2==0?135:405),192+(i<2?h/4:3*h/4)),expected[orientation-1][i]);}
        finally {out.flush();}
    }
    @Test void additionalRotationAndResolvedCropAreExecuted() throws Exception {
        var file=image();var assets=assets(List.of(file));
        var rotated=ImageIO.read(new ByteArrayInputStream(render(plan(assets,"photo-1",90,false,NEUTRAL),assets,List.of(file))));
        try {near(rotated.getRGB(300,400),Color.RED);near(rotated.getRGB(300,1100),Color.BLUE);}finally{rotated.flush();}
        var base=plan(assets,"photo-1",0,false,NEUTRAL);var image=base.layers().images().getFirst();
        var cropped=new ImageLayer("photo-1",0,image.x(),image.y(),.5,.28125,new ImageCrop(.5,0.,.5,1.),0,NEUTRAL);
        var cropPlan=new StoryRenderPlan(base.schemaVersion(),base.canvas(),base.background(),base.layoutType(),new StoryLayers(List.of(cropped),List.of()),base.renderingOptions());
        var out=ImageIO.read(new ByteArrayInputStream(render(cropPlan,assets,List.of(file))));
        try {near(out.getRGB(200,400),Color.BLUE);near(out.getRGB(550,600),Color.BLUE);}finally{out.flush();}
    }
    @Test void adjustmentsAndCaptionChangeOnlyIntendedRegions() throws Exception {
        var file=image();var assets=assets(List.of(file));
        var grayscale=ImageIO.read(new ByteArrayInputStream(render(plan(assets,"photo-1",0,false,new ImageAdjustments(0.,1.,0.)),assets,List.of(file))));
        try {near(grayscale.getRGB(200,350),new Color(54,54,54));}finally{grayscale.flush();}
        var without=ImageIO.read(new ByteArrayInputStream(render(plan(assets,"photo-1",0,false,NEUTRAL),assets,List.of(file))));
        var captionPng=render(plan(assets,"photo-1",0,true,NEUTRAL),assets,List.of(file));
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target","render-samples"));
        java.nio.file.Files.write(java.nio.file.Path.of("target","render-samples","single-photo-caption.png"),captionPng);
        var with=ImageIO.read(new ByteArrayInputStream(captionPng));
        try {int changed=0;for(int y=1440;y<1632;y++)for(int x=108;x<972;x++)if(with.getRGB(x,y)!=without.getRGB(x,y))changed++;
            assertThat(changed).isGreaterThan(100);assertThat(with.getRGB(200,350)).isEqualTo(without.getRGB(200,350));
        }finally{with.flush();without.flush();}
    }
    @Test void fullOrderedSetSupportsPhotoTwoAndRejectsSwapsMissingAndBadHashes() throws Exception {
        var first=image();var second=new MockMultipartFile("photos","second.jpg","image/jpeg",ProcessingTestImages.pattern(80,40));
        List<MultipartFile> files=List.of(first,second);var assets=assets(files);var plan=plan(assets,"photo-2",0,false,NEUTRAL);
        assertThat(render(plan,assets,files)).isNotEmpty();
        assertThatThrownBy(()->render(plan,assets,List.of(second,first))).isInstanceOfSatisfying(RenderRequestException.class,e->assertThat(e.code()).isEqualTo("RENDER_ASSET_MISMATCH"));
        assertThatThrownBy(()->render(plan,assets,List.of(second))).isInstanceOf(RenderRequestException.class);
        var original=assets.get(0);var wrong=new PhotoAssetMetadata(original.photoId(),"0".repeat(64),original.originalWidth(),original.originalHeight(),original.mediaType(),original.orientedWidth(),original.orientedHeight());
        assertThatThrownBy(()->render(plan,List.of(wrong,assets.get(1)),files)).isInstanceOf(RenderRequestException.class);
    }
    @ParameterizedTest @CsvSource({"0,1,100", "1,1,200", "0,1.5,86", "1,1.5,236", "-1,1,50"})
    void exposureContrastAndNeutralValuesFollowDeclaredMath(double exposure,double contrast,int expected) throws Exception {
        var source=new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB);var g=source.createGraphics();byte[] bytes;
        try {g.setColor(new Color(100,100,100));g.fillRect(0,0,80,40);var buffer=new ByteArrayOutputStream();ImageIO.write(source,"png",buffer);bytes=buffer.toByteArray();}
        finally{g.dispose();source.flush();}
        var file=new MockMultipartFile("photos","gray.png","image/png",bytes);var assets=assets(List.of(file));
        var out=ImageIO.read(new ByteArrayInputStream(render(plan(assets,"photo-1",0,false,new ImageAdjustments(exposure,contrast,1.)),assets,List.of(file))));
        try{assertThat(new Color(out.getRGB(300,350)).getRed()).isEqualTo(expected);}finally{out.flush();}
    }
    @Test void doesNotDecodeUnusedOriginalAndRejectsMalformedSelectedOriginal() throws Exception {
        var selected=image();var unused=image();
        List<MultipartFile> files=List.of(unused,selected);var assets=assets(files);var plan=plan(assets,"photo-2",0,false,NEUTRAL);
        try(var decoder=org.mockito.Mockito.mockStatic(OriginalPhotoDecoder.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            assertThat(render(plan,assets,files)).isNotEmpty();
            decoder.verify(()->OriginalPhotoDecoder.decode(selected,assets.get(1)),org.mockito.Mockito.times(1));
            decoder.verifyNoMoreInteractions();
        }
        var invalid=new MockMultipartFile("photos","fake.jpg","image/jpeg",new byte[]{1,2,3});
        assertThatThrownBy(()->render(plan,assets,List.of(unused,invalid))).isInstanceOf(com.storystylist.backend.photo.exception.InvalidPhotoException.class);
    }
}
