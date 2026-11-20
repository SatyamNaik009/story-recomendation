package com.storystylist.backend.render.service;
import com.storystylist.backend.render.model.*;
import com.storystylist.backend.render.validation.*;
import com.storystylist.backend.photo.service.OriginalPhotoDecoder;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.*;
import net.coobird.thumbnailator.util.exif.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.Map;
@Service
public class Java2DStoryRenderer {
    private final RenderSpecificationValidator validator;
    private final TextLayoutProperties textSettings;
    private final StoryImageEncodingService encoding;
    public Java2DStoryRenderer(RenderSpecificationValidator validator,TextLayoutProperties textSettings,StoryImageEncodingService encoding) {
        this.validator=validator;this.textSettings=textSettings;this.encoding=encoding;
    }
    public byte[] render(CompiledStorySpecification spec,Map<String,MultipartFile> originals) {
        return render(spec,originals,false);
    }
    public byte[] render(CompiledStorySpecification spec,Map<String,MultipartFile> originals,boolean refinedSingle) {
        validator.validate(spec);
        if(spec.layers().stream().filter(CompiledImageLayer.class::isInstance).count()!=1) throw new RenderRequestException("RENDER_LAYOUT_UNSUPPORTED","Only SINGLE layouts can be rendered in this milestone",422);
        var canvas=new BufferedImage(1080,1920,BufferedImage.TYPE_INT_RGB);
        var graphics=canvas.createGraphics();
        try {
            hints(graphics);graphics.setColor(Color.decode(spec.background().color()));graphics.fillRect(0,0,1080,1920);
            for(var layer:spec.layers()) {
                if(layer instanceof CompiledImageLayer image) {
                    var asset=spec.assets().stream().filter(a->a.photoId().equals(image.photoId())).findFirst().orElseThrow();
                    var file=originals.get(image.photoId());
                    if(file==null) throw RenderRequestException.invalid("RENDER_PHOTO_MISSING","Selected original photograph is missing");
                    var original=OriginalPhotoDecoder.decode(file,asset);BufferedImage rotated=null,tile=null;
                    try {
                        rotated=switch(image.rotation()) {
                            case 0->original;
                            case 90->ExifFilterUtils.getFilterForOrientation(Orientation.RIGHT_TOP).apply(original);
                            case 180->ExifFilterUtils.getFilterForOrientation(Orientation.BOTTOM_RIGHT).apply(original);
                            case 270->ExifFilterUtils.getFilterForOrientation(Orientation.LEFT_BOTTOM).apply(original);
                            default->throw new IllegalArgumentException();
                        };
                        if(refinedSingle)SinglePhotoBackdrop.paint(graphics,rotated);
                        var d=image.destination();var crop=image.sourceCrop();
                        tile=new BufferedImage(d.width(),d.height(),BufferedImage.TYPE_INT_RGB);
                        var tg=tile.createGraphics();
                        try {
                            hints(tg);tg.setColor(Color.WHITE);tg.fillRect(0,0,d.width(),d.height());
                            // An enclosing source view bounds interpolation support; the continuous crop remains authoritative.
                            int left=Math.max(0,(int)Math.floor(crop.x())),top=Math.max(0,(int)Math.floor(crop.y()));
                            int right=Math.min(rotated.getWidth(),(int)Math.ceil(crop.x()+crop.width()));
                            int bottom=Math.min(rotated.getHeight(),(int)Math.ceil(crop.y()+crop.height()));
                            var view=rotated.getSubimage(left,top,right-left,bottom-top);
                            double scale=d.width()/crop.width();
                            var transform=new AffineTransform();transform.scale(scale,scale);
                            transform.translate(-(crop.x()-left),-(crop.y()-top));
                            tg.clipRect(0,0,d.width(),d.height());tg.drawImage(view,transform,null);
                        } finally {tg.dispose();}
                        adjust(tile,image.adjustments());graphics.drawImage(tile,d.x(),d.y(),null);
                    } finally {if(tile!=null)tile.flush();if(rotated!=null && rotated!=original)rotated.flush();original.flush();}
                } else if(layer instanceof CompiledTextLayer text) drawCaption(graphics,text);
            }
            return encoding.png(canvas);
        } catch(RenderRequestException e){throw e;}
        catch(com.storystylist.backend.ai.exception.AiException e){throw RenderRequestException.invalid("RENDER_TEXT_INVALID","Caption layout cannot be reproduced on this runtime");}
        catch(RuntimeException e){throw new RenderRequestException("RENDER_FAILED","Could not render the Story image",500);}
        finally{graphics.dispose();canvas.flush();}
    }
    private void drawCaption(Graphics2D graphics,CompiledTextLayer text) {
        var b=text.box();
        var layer=new TextLayer(text.text(),text.order(),b.x()/1080,b.y()/1920,b.width()/1080,b.height()/1920,text.font(),text.fontSize(),text.color(),text.alignment(),text.rotation(),text.layout());
        var glyphs=TextLayoutResolver.layoutsForRendering(layer,textSettings);
        var g=(Graphics2D)graphics.create();
        try {
            hints(g);g.setColor(Color.decode(text.color()));g.clip(new Rectangle2D.Double(b.x(),b.y(),b.width(),b.height()));
            for(int i=0;i<glyphs.size();i++) {
                var line=text.layout().lines().get(i);
                if(!line.text().isEmpty())glyphs.get(i).draw(g,(float)(b.x()+line.xOffset()),(float)(b.y()+line.baseline()));
            }
        } finally {g.dispose();}
    }
    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }
    static void adjust(BufferedImage image,ImageAdjustments edits) {
        if(edits.exposure()==0 && edits.contrast()==1 && edits.saturation()==1)return;
        double exposure=Math.pow(2,edits.exposure());int[] row=new int[image.getWidth()];
        for(int y=0;y<image.getHeight();y++) {
            image.getRGB(0,y,row.length,1,row,0,row.length);
            for(int x=0;x<row.length;x++) {
                int rgb=row[x];double r=(((rgb>>16)&255)/255.*exposure-.5)*edits.contrast()+.5;
                double g=(((rgb>>8)&255)/255.*exposure-.5)*edits.contrast()+.5;
                double b=((rgb&255)/255.*exposure-.5)*edits.contrast()+.5;
                double luminance=.2126*r+.7152*g+.0722*b;
                row[x]=(channel(luminance+edits.saturation()*(r-luminance))<<16)
                        |(channel(luminance+edits.saturation()*(g-luminance))<<8)|channel(luminance+edits.saturation()*(b-luminance));
            }
            image.setRGB(0,y,row.length,1,row,0,row.length);
        }
    }
    private static int channel(double value){return (int)Math.round(Math.max(0,Math.min(1,value))*255);}
}
