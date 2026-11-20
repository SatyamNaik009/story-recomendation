package com.storystylist.backend.story.service;
import com.storystylist.backend.story.dto.*;
import java.util.*;
/** Pure geometry, never expands the preferred region or modifies source images. */
public final class CoverCropResolver {
    public static final double EPSILON = 1e-9;
    private CoverCropResolver() {}
    private static void check(boolean ok, String rule) { if(!ok) throw new InvalidRecommendationException(rule); }
    private static boolean finite(Double n) { return n != null && Double.isFinite(n); }
    private static void rectangle(Double x, Double y, Double w, Double h) {
        check(finite(x)&&finite(y)&&finite(w)&&finite(h),"cover.nonFiniteCoordinates");
        check(x>=0 && y>=0 && w>0 && h>0 && x+w<=1 && y+h<=1,"cover.invalidBounds");
    }
    public static ImageCrop resolve(ImageLayer image, PhotoAssetMetadata asset) {
        check(image!=null && asset!=null,"cover.sourceRequired");
        check(Objects.equals(image.photoId(),asset.photoId()),"cover.sourceReference");
        check(image.fitMode()==ImageFitMode.COVER,"cover.fitMode");
        check(asset.orientedWidth()!=null && asset.orientedHeight()!=null && asset.orientedWidth()>0 && asset.orientedHeight()>0,"cover.sourceDimensions");
        check(image.rotation()!=null && Set.of(0,90,180,270).contains(image.rotation()),"cover.rotation");
        rectangle(image.x(),image.y(),image.width(),image.height());
        var p=image.crop(); check(p!=null,"cover.cropRequired");
        rectangle(p.x(),p.y(),p.width(),p.height());
        var focal=image.focalPoint();
        if(focal!=null) check(finite(focal.x()) && finite(focal.y()) && focal.x()>=0 && focal.x()<=1 && focal.y()>=0 && focal.y()<=1,"cover.focalPoint");
        double sw=asset.orientedWidth(), sh=asset.orientedHeight();
        if(image.rotation()==90 || image.rotation()==270) { double temp=sw;sw=sh;sh=temp; }
        double target=(image.width()*1080)/(image.height()*1920);
        double ratio=(p.width()*sw)/(p.height()*sh);
        check(Double.isFinite(target) && target>0 && Double.isFinite(ratio) && ratio>0,"cover.invalidAspectRatio");
        // Avoid numerical drift when finalizing an already normalized plan.
        if(Math.abs(ratio/target-1)<=EPSILON) return p;
        double w=p.width(),h=p.height();
        if(ratio>target) w=h*sh*target/sw;
        else h=w*sw/(target*sh);
        double fx=focal==null ? p.x()+p.width()/2 : focal.x();
        double fy=focal==null ? p.y()+p.height()/2 : focal.y();
        double x=Math.max(p.x(),Math.min(fx-w/2,p.x()+p.width()-w));
        double y=Math.max(p.y(),Math.min(fy-h/2,p.y()+p.height()-h));
        check(Double.isFinite(w)&&Double.isFinite(h)&&w>0&&h>0,"cover.invalidResolvedSize");
        check(x>=p.x()-EPSILON && y>=p.y()-EPSILON && x+w<=p.x()+p.width()+EPSILON && y+h<=p.y()+p.height()+EPSILON,"cover.preferredBounds");
        check(Math.abs((w*sw/(h*sh))/target-1)<=EPSILON,"cover.resolvedAspectRatio");
        return new ImageCrop(x,y,w,h);
    }
    public static StoryAiResult resolve(StoryAiResult input, List<PhotoAssetMetadata> assets) {
        Map<String,PhotoAssetMetadata> sources=new HashMap<>();
        assets.forEach(a->sources.put(a.photoId(),a));
        return new StoryAiResult(input.recommendations().stream().map(d->{
            var p=d.renderPlan();
            var images=p.layers().images().stream().map(i->new ImageLayer(i.photoId(),i.order(),i.x(),i.y(),i.width(),i.height(),
                    resolve(i,sources.get(i.photoId())),i.rotation(),i.adjustments(),i.fitMode(),i.focalPoint())).toList();
            return new StoryDraft(d.summary(),new StoryRenderPlan(p.schemaVersion(),p.canvas(),p.background(),p.layoutType(),
                    new StoryLayers(images,p.layers().texts()),p.renderingOptions()));
        }).toList());
    }
}
