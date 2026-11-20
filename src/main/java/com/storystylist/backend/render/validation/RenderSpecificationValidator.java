package com.storystylist.backend.render.validation;
import com.storystylist.backend.render.model.*;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.*;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class RenderSpecificationValidator {
    private final TextLayoutProperties textSettings;
    public RenderSpecificationValidator(TextLayoutProperties textSettings) { this.textSettings=textSettings; }
    public static void require(boolean ok,String rule) { if(!ok) throw new InvalidRenderSpecificationException(rule); }
    private static void color(String value) { require(value!=null && value.matches("#[0-9a-fA-F]{6}"),"color.unsupported"); }
    private static void number(Double v,double low,double high) { require(v!=null && Double.isFinite(v) && v>=low && v<=high,"number.outOfRange"); }
    public static void validateAsset(PhotoAssetMetadata a) {
        require(a!=null && a.photoId()!=null && !a.photoId().isBlank(),"asset.identity");
        require(a.sha256()!=null && a.sha256().matches("[0-9a-fA-F]{64}"),"asset.sha256");
        require("image/jpeg".equals(a.mediaType()) || "image/png".equals(a.mediaType()),"asset.mediaType");
        require(a.originalWidth()!=null && a.originalHeight()!=null && a.orientedWidth()!=null && a.orientedHeight()!=null
                && a.originalWidth()>0 && a.originalHeight()>0 && a.orientedWidth()>0 && a.orientedHeight()>0,"asset.dimensions");
        require((a.originalWidth().equals(a.orientedWidth()) && a.originalHeight().equals(a.orientedHeight()))
                || (a.originalWidth().equals(a.orientedHeight()) && a.originalHeight().equals(a.orientedWidth())),"asset.orientationDimensions");
        if("image/png".equals(a.mediaType())) require(a.originalWidth().equals(a.orientedWidth()) && a.originalHeight().equals(a.orientedHeight()),"asset.pngOrientation");
    }
    public void validate(CompiledStorySpecification s) {
        try {
            require(s!=null && "compiled-story-v1".equals(s.specificationVersion()),"specification.version");
            require(s.recommendationId()!=null && !s.recommendationId().isBlank(),"recommendation.id");
            require(new StoryCanvas(1080,1920).equals(s.canvas()),"canvas.dimensions");
            require(RenderOutputOptions.png().equals(s.output()),"output.pngRequired");
            require(s.background()!=null && s.background().type()==BackgroundType.SOLID,"background.solidRequired");color(s.background().color());
            Map<String,PhotoAssetMetadata> assets=new LinkedHashMap<>();
            for(var a:s.assets()) { validateAsset(a); require(assets.put(a.photoId(),a)==null,"asset.duplicateId"); }
            Set<String> used=new HashSet<>();var images=new ArrayList<CompiledImageLayer>(); int order=0,textCount=0;
            for(var layer:s.layers()) {
                require(layer!=null && layer.order()==order++,"layer.order");
                if(layer instanceof CompiledImageLayer image) {
                    require(textCount==0,"layer.imagesBeforeText");
                    require(assets.containsKey(image.photoId()) && used.add(image.photoId()),"image.assetReference");
                    var d=image.destination();require(d!=null && d.x()>=0 && d.y()>=0 && d.width()>0 && d.height()>0
                            && (long)d.x()+d.width()<=1080 && (long)d.y()+d.height()<=1920,"image.destination");
                    require(Set.of(0,90,180,270).contains(image.rotation()) && image.fitMode()==ImageFitMode.COVER,"image.transform");
                    var a=assets.get(image.photoId());double sw=a.orientedWidth(),sh=a.orientedHeight();
                    if(image.rotation()==90 || image.rotation()==270) { double temp=sw;sw=sh;sh=temp; }
                    var c=image.sourceCrop();require(c!=null,"image.crop");
                    number(c.x(),0,sw);number(c.y(),0,sh);number(c.width(),Double.MIN_VALUE,sw);number(c.height(),Double.MIN_VALUE,sh);
                    require(c.x()+c.width()<=sw+1e-7 && c.y()+c.height()<=sh+1e-7,"image.cropBounds");
                    require(Math.abs((c.width()/c.height())/((double)d.width()/d.height())-1)<=CoverCropResolver.EPSILON,"image.aspectRatio");
                    var edits=image.adjustments();require(edits!=null,"image.adjustments");number(edits.exposure(),-1,1);number(edits.contrast(),.5,1.5);number(edits.saturation(),0,1.5);
                    images.add(image);
                } else if(layer instanceof CompiledTextLayer text) {
                    require(++textCount<=1,"text.count");var b=text.box();require(b!=null,"text.box");
                    number(b.x(),0,1080);number(b.y(),192,1728);number(b.width(),Double.MIN_VALUE,1080);number(b.height(),Double.MIN_VALUE,1920);
                    require(b.x()+b.width()<=1080+1e-7 && b.y()+b.height()<=1728+1e-7,"text.boxBounds");
                    require(text.rotation()==0 && text.font()==FontId.SANS_SERIF && text.alignment()!=null,"text.style");color(text.color());
                    TextLayoutResolver.validateFinal(new TextLayer(text.text(),text.order(),b.x()/1080,b.y()/1920,b.width()/1080,b.height()/1920,
                            text.font(),text.fontSize(),text.color(),text.alignment(),text.rotation(),text.layout()),textSettings);
                } else throw new InvalidRenderSpecificationException("layer.unsupported");
            }
            require(images.size()>=1 && images.size()<=4 && used.equals(assets.keySet()),"assets.selectedOnly");
            for(int i=0;i<images.size();i++) for(int j=i+1;j<images.size();j++) {
                var a=images.get(i).destination();var b=images.get(j).destination();
                require(Math.min(a.x()+a.width(),b.x()+b.width())<=Math.max(a.x(),b.x())
                        || Math.min(a.y()+a.height(),b.y()+b.height())<=Math.max(a.y(),b.y()),"images.overlap");
            }
        } catch(InvalidRenderSpecificationException e) { throw e; }
        catch(RuntimeException e) { throw new InvalidRenderSpecificationException("specification.invalidStructureOrText"); }
    }
}
