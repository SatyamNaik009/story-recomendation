package com.storystylist.backend.render;
import com.storystylist.backend.render.service.*;
import com.storystylist.backend.render.model.*;
import com.storystylist.backend.render.validation.*;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.*;
import com.storystylist.backend.ai.validation.VisionResponseCodec;
import com.storystylist.backend.photo.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static com.storystylist.backend.ai.AiTestData.JSON;
import java.util.List;
class SinglePhotoPresentationTests {
 static final TextLayoutProperties TEXT=TextLayoutProperties.defaults();
 static SinglePhotoLayoutRefiner refiner(boolean enabled){return new SinglePhotoLayoutRefiner(new SinglePhotoPresentationProperties(enabled),TEXT);}
 static RenderSpecificationCompiler compiler(){return new RenderSpecificationCompiler(new RecommendationValidationService(),new RenderSpecificationValidator(TEXT));}
 static StoryRenderer renderer(boolean enabled){
  var v=new RenderSpecificationValidator(TEXT);
  return new StoryRenderer(new VisionResponseCodec(),new OriginalPhotoAssetResolver(StoryRenderTests.validation()),compiler(),new Java2DStoryRenderer(v,TEXT,new StoryImageEncodingService()),refiner(enabled));
 }
 @ParameterizedTest @CsvSource({"1200,800,0","800,1200,0","800,1200,90","1200,800,270","4000,500,0","500,1200,180"})
 void fullContextFitIsLargerBalancedAndValid(int w,int h,int rotation) throws Exception {
  var file=TestImages.photo("png",w,h);var assets=StoryRenderTests.assets(List.of(file));
  var old=StoryRenderTests.plan(assets,"photo-1",rotation,true,StoryRenderTests.NEUTRAL);
  var improved=refiner(true).refine(old,assets);var image=improved.layers().images().getFirst();
  assertThat(image.crop()).isEqualTo(new ImageCrop(0.,0.,1.,1.));
  assertThat(image.photoId()).isEqualTo("photo-1");assertThat(image.rotation()).isEqualTo(rotation);
  assertThat(image.width()*image.height()).isGreaterThan(old.layers().images().getFirst().width()*old.layers().images().getFirst().height());
  var text=improved.layers().texts().getFirst();assertThat(text.text()).isEqualTo(old.layers().texts().getFirst().text());
  assertThat(text.y()*1920-(image.y()+image.height())*1920).isCloseTo(40,within(1e-8));
  assertThat(text.y()).isGreaterThanOrEqualTo(.1);assertThat(text.y()+text.height()).isLessThanOrEqualTo(.9);
  assertThat(refiner(true).refine(improved,assets)).isEqualTo(improved);
  var spec=compiler().compilePlan("test",improved,assets);
  var compiled=(CompiledImageLayer)spec.layers().getFirst();
  assertThat(compiled.sourceCrop().width()*compiled.sourceCrop().height()/(w*(double)h)).isGreaterThan(.99);
 }
 @Test void widensTightCropAndPreservesBothEdgeSubjectsEvenWithEdgeFocalPoint() throws Exception {
  var file=StoryRenderTests.image();var assets=StoryRenderTests.assets(List.of(file));
  var base=StoryRenderTests.plan(assets,"photo-1",0,true,StoryRenderTests.NEUTRAL);
  var tight=new ImageLayer("photo-1",0,.25,.2,.5,.28125,new ImageCrop(.25,0.,.5,1.),0,StoryRenderTests.NEUTRAL,ImageFitMode.COVER,new FocalPoint(.98,.5));
  var old=new StoryRenderPlan(base.schemaVersion(),base.canvas(),base.background(),base.layoutType(),new StoryLayers(List.of(tight),base.layers().texts()),base.renderingOptions());
  compiler().compilePlan("test",old,assets);
  var improved=refiner(true).refine(old,assets);var image=improved.layers().images().getFirst();
  assertThat(image.crop().width()).isGreaterThan(tight.crop().width());assertThat(image.crop().x()).isZero();
  assertThat(image.focalPoint()).isEqualTo(tight.focalPoint());assertThat(image.crop().x()+image.crop().width()).isEqualTo(1);
  var png=renderer(true).render(JSON.writeValueAsString(old),JSON.writeValueAsString(assets),"test",List.of(file));
  var actual=ImageIO.read(new ByteArrayInputStream(png));
  try {int y=(int)Math.round(image.y()*1920);
   StoryRenderTests.near(actual.getRGB(65,y+12),Color.GREEN);
   StoryRenderTests.near(actual.getRGB(1010,y+200),Color.BLUE);
  }finally{actual.flush();}
 }
 @Test void preservesCaptionAbovePhotoAndImprovesLowContrastWithoutInventingText() throws Exception {
  var file=StoryRenderTests.image();var assets=StoryRenderTests.assets(List.of(file));var base=StoryRenderTests.plan(assets,"photo-1",0,true,StoryRenderTests.NEUTRAL);
  var t=base.layers().texts().getFirst();
  var above=TextLayoutResolver.resolve(new TextLayer(t.text(),1,.1,.1,.8,.1,t.font(),24,"#102030",TextAlignment.LEFT,0),TEXT);
  var oldImage=base.layers().images().getFirst();var below=new ImageLayer("photo-1",0,oldImage.x(),.4,oldImage.width(),oldImage.height(),oldImage.crop(),0,oldImage.adjustments());
  var plan=new StoryRenderPlan(base.schemaVersion(),base.canvas(),base.background(),base.layoutType(),new StoryLayers(List.of(below),List.of(above)),base.renderingOptions());
  var result=refiner(true).refine(plan,assets);var text=result.layers().texts().getFirst();var photo=result.layers().images().getFirst();
  assertThat(text.y()+text.height()).isLessThan(photo.y());assertThat(text.text()).isEqualTo(t.text());
  assertThat(text.color()).isEqualTo("#FFFFFF");assertThat(text.alignment()).isEqualTo(TextAlignment.LEFT);assertThat(text.fontSize()).isEqualTo(40);
  compiler().compilePlan("test",result,assets);
 }
 @Test void disabledPolicyIsExactlyLegacyAndNoCaptionIsAdded() throws Exception {
  var file=StoryRenderTests.image();var assets=StoryRenderTests.assets(List.of(file));var plan=StoryRenderTests.plan(assets,"photo-1",0,false,StoryRenderTests.NEUTRAL);
  assertThat(refiner(false).refine(plan,assets)).isSameAs(plan);assertThat(refiner(true).refine(plan,assets).layers().texts()).isEmpty();
  assertThat(renderer(false).render(JSON.writeValueAsString(plan),JSON.writeValueAsString(assets),"test",List.of(file))).isEqualTo(StoryRenderTests.render(plan,assets,List.of(file)));
 }
 @Test void unsupportedOrInvalidInputIsNotRepairedIntoSuccess() throws Exception {
  var file=StoryRenderTests.image();var assets=StoryRenderTests.assets(List.of(file));var plan=StoryRenderTests.plan(assets,"photo-1",0,true,StoryRenderTests.NEUTRAL);
  String json=JSON.writeValueAsString(plan);
  assertThatThrownBy(()->renderer(true).render(json.replace("photo-1","unknown"),JSON.writeValueAsString(assets),"test",List.of(file))).isInstanceOf(RenderRequestException.class);
  assertThatThrownBy(()->renderer(true).render(json.replace("SINGLE","SPLIT_TWO"),JSON.writeValueAsString(assets),"test",List.of(file))).isInstanceOf(RenderRequestException.class);
 }
 @Test void producesDeterministicBeforeAfterPngComparison() throws Exception {
  var photo=new BufferedImage(1200,800,BufferedImage.TYPE_INT_RGB);var g=photo.createGraphics();
  try {
   g.setPaint(new GradientPaint(0,0,new Color(112,161,173),1200,800,new Color(225,190,139)));g.fillRect(0,0,1200,800);
   g.setColor(new Color(86,111,80));g.fillRect(0,550,1200,250);
   for(int x:new int[]{70,350,650,1050}){g.setColor(new Color(211,152,118));g.fillOval(x,270,90,90);g.setColor(new Color(40+x/12,65,80));g.fillRoundRect(x-10,360,110,240,20,20);}
  }finally{g.dispose();}
  var bytes=new ByteArrayOutputStream();ImageIO.write(photo,"png",bytes);photo.flush();
  var file=new MockMultipartFile("photos","synthetic-group.png","image/png",bytes.toByteArray());var assets=StoryRenderTests.assets(List.of(file));
  var base=StoryRenderTests.plan(assets,"photo-1",0,true,StoryRenderTests.NEUTRAL);
  var image=new ImageLayer("photo-1",0,.25,.2,.5,.375,new ImageCrop(.25,0.,.5,1.),0,StoryRenderTests.NEUTRAL);
  var plan=new StoryRenderPlan(base.schemaVersion(),base.canvas(),base.background(),base.layoutType(),new StoryLayers(List.of(image),base.layers().texts()),base.renderingOptions());
  var before=renderer(false).render(JSON.writeValueAsString(plan),JSON.writeValueAsString(assets),"test",List.of(file));
  var after=renderer(true).render(JSON.writeValueAsString(plan),JSON.writeValueAsString(assets),"test",List.of(file));
  assertThat(after).isEqualTo(renderer(true).render(JSON.writeValueAsString(plan),JSON.writeValueAsString(assets),"test",List.of(file)));
  var output=ImageIO.read(new ByteArrayInputStream(after));try{assertThat(output.getWidth()).isEqualTo(1080);assertThat(output.getHeight()).isEqualTo(1920);}finally{output.flush();}
  var directory=java.nio.file.Path.of("target","render-samples");java.nio.file.Files.createDirectories(directory);
  java.nio.file.Files.write(directory.resolve("single-before.png"),before);java.nio.file.Files.write(directory.resolve("single-after.png"),after);
 }
}

