package com.storystylist.backend.render.service;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.util.List;
/** Version 1: full-context fit, balanced grouping, original caption and source identity. */
@Component
@EnableConfigurationProperties({SinglePhotoPresentationProperties.class,TextLayoutProperties.class})
public class SinglePhotoLayoutRefiner {
 private final SinglePhotoPresentationProperties settings;
 private final TextLayoutProperties textSettings;
 public SinglePhotoLayoutRefiner(SinglePhotoPresentationProperties settings,TextLayoutProperties textSettings){this.settings=settings;this.textSettings=textSettings;}
 public boolean enabled(){return settings.refinementEnabled();}
 /** Input must already pass the original compiler/validation boundary. Never mutates the saved plan. */
 public StoryRenderPlan refine(StoryRenderPlan plan,List<PhotoAssetMetadata> assets) {
  if(!enabled() || plan.layoutType()!=StoryLayoutType.SINGLE)return plan;
  try {return refineLayout(plan,assets);}
  catch(com.storystylist.backend.ai.exception.AiException cannotFit){return plan;}
 }
 private StoryRenderPlan refineLayout(StoryRenderPlan plan,List<PhotoAssetMetadata> assets) {
  var image=plan.layers().images().getFirst();
  var source=assets.stream().filter(a->a.photoId().equals(image.photoId())).findFirst().orElseThrow();
  double sw=source.orientedWidth(),sh=source.orientedHeight();
  if(image.rotation()==90 || image.rotation()==270){double tmp=sw;sw=sh;sh=tmp;}
  TextLayer caption=plan.layers().texts().isEmpty()?null:plan.layers().texts().getFirst();
  boolean above=caption!=null && caption.y()+caption.height()/2<image.y()+image.height()/2;
  TextLayer measured=null;double captionHeight=0,gap=caption==null?0:40;
  if(caption!=null) {
   int size=Math.max(textSettings.minFontSize(),Math.min(textSettings.maxFontSize(),Math.max(40,Math.min(52,caption.fontSize()))));
   // Retain enough height for captions that were already valid at unusually large configured minimum sizes.
   measured=TextLayoutResolver.resolve(new TextLayer(caption.text(),caption.order(),.08,.1,.84,.8,caption.font(),size,
    readable(caption.color(),plan.background().color()),caption.alignment(),0),textSettings);
   var lines=measured.resolvedLayout().lines();
   captionHeight=Math.ceil(lines.getLast().baseline()+measured.fontSize()*.4+TextLayoutResolver.PADDING);
  }
  double availableHeight=(caption==null?1728:1536)-captionHeight-gap;
  if(availableHeight<192)return plan;
  double scale=Math.min(972/sw,availableHeight/sh);
  double width=sw*scale,height=sh*scale;
  if(width<2 || height<2)return plan;
  double top=(1920-height-captionHeight-gap)/2;
  double imageY=above?top+captionHeight+gap:top;
  var refinedImage=new ImageLayer(image.photoId(),image.order(),(1080-width)/2160,imageY/1920,width/1080,height/1920,
   new ImageCrop(0.,0.,1.,1.),image.rotation(),image.adjustments(),image.fitMode(),image.focalPoint());
  List<TextLayer> texts=List.of();
  if(measured!=null) {
   double textY=above?top:top+height+gap;
   texts=List.of(TextLayoutResolver.resolve(new TextLayer(measured.text(),measured.order(),.08,textY/1920,.84,captionHeight/1920,
    measured.font(),measured.fontSize(),measured.color(),measured.alignment(),0),textSettings));
  }
  return new StoryRenderPlan(plan.schemaVersion(),plan.canvas(),plan.background(),plan.layoutType(),new StoryLayers(List.of(refinedImage),texts),plan.renderingOptions());
 }
 private static String readable(String foreground,String background) {
  double a=luminance(foreground),b=luminance(background);
  if((Math.max(a,b)+.05)/(Math.min(a,b)+.05)>=7)return foreground;
  return b>.179?"#000000":"#FFFFFF";
 }
 private static double luminance(String hex){
  int rgb=Integer.parseInt(hex.substring(1),16);double[] c={((rgb>>16)&255)/255.,((rgb>>8)&255)/255.,(rgb&255)/255.};
  for(int i=0;i<3;i++)c[i]=c[i]<=.04045?c[i]/12.92:Math.pow((c[i]+.055)/1.055,2.4);
  return .2126*c[0]+.7152*c[1]+.0722*c[2];
 }
}
