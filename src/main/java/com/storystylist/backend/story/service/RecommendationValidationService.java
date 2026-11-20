package com.storystylist.backend.story.service;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.ai.exception.*;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
@org.springframework.boot.context.properties.EnableConfigurationProperties(TextLayoutProperties.class)
public class RecommendationValidationService {
    private final TextLayoutProperties textSettings;
    public RecommendationValidationService() { this(TextLayoutProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public RecommendationValidationService(TextLayoutProperties textSettings) { this.textSettings=textSettings; }
    TextLayoutProperties textSettings() { return textSettings; }
    private static void require(boolean valid, String rule) { if (!valid) throw new InvalidRecommendationException(rule); }
    private static void text(String s, int max, String field) { require(s != null && !s.isBlank() && s.length() <= max, field+".requiredOrLength"); }
    private static void number(Double v, double min, double max, String field) { require(v != null && Double.isFinite(v) && v >= min && v <= max, field+".finiteOrRange"); }
    private static void color(String s, String field) { require(s != null && s.matches("#[0-9A-Fa-f]{6}"), field+".hexRgb"); }
    private static void box(Double x, Double y, Double w, Double h, String field) {
        number(x,0,1,field+".x"); number(y,0,1,field+".y"); number(w,Double.MIN_VALUE,1,field+".width"); number(h,Double.MIN_VALUE,1,field+".height");
        require(x+w <= 1.000000001 && y+h <= 1.000000001, field+".canvasBounds");
    }
    public List<StoryDraft> validate(StoryAiResult result, List<PhotoAssetMetadata> assets) {
        return validate(result,assets,true);
    }
    public List<StoryDraft> validatePreferred(StoryAiResult result, List<PhotoAssetMetadata> assets) {
        return validate(result,assets,false);
    }
    private List<StoryDraft> validate(StoryAiResult result, List<PhotoAssetMetadata> assets, boolean finalized) {
        try {
            require(result != null && result.recommendations() != null, "recommendations.required");
            require(result.recommendations().size() >= 1 && result.recommendations().size() <= 3, "recommendations.count");
            Map<String,PhotoAssetMetadata> sources = new HashMap<>();
            assets.forEach(a -> sources.put(a.photoId(),a));
            Set<StoryRenderPlan> unique = new HashSet<>();
            for (var draft : result.recommendations()) {
                require(draft != null && draft.summary() != null && draft.renderPlan() != null, "recommendation.summaryAndPlanRequired");
                var s = draft.summary(); var p = draft.renderPlan();
                text(s.title(),80,"summary.title"); text(s.concept(),400,"summary.concept"); text(s.selectionReason(),400,"summary.selectionReason");
                text(s.layoutSummary(),300,"summary.layoutSummary"); text(s.whyItWorks(),400,"summary.whyItWorks");
                require(s.selectedPhotoIds() != null && s.selectedPhotoIds().size() >= 1 && s.selectedPhotoIds().size() <= 4, "selectedPhotos.count");
                var ids = new HashSet<>(s.selectedPhotoIds());
                require(ids.size() == s.selectedPhotoIds().size() && sources.keySet().containsAll(ids), "selectedPhotos.unknownOrDuplicate");
                require(unique.add(p), "recommendations.duplicatePlan");
                require("story-render-plan-v3".equals(p.schemaVersion()), "plan.schemaVersion");
                require(p.canvas() != null && Integer.valueOf(1080).equals(p.canvas().width()) && Integer.valueOf(1920).equals(p.canvas().height()), "canvas.dimensions");
                require(p.background() != null && p.background().type() == BackgroundType.SOLID, "background.type"); color(p.background().color(),"background.color");
                require(p.layoutType() != null && p.layoutType().ordinal()+1 == ids.size(), "layout.photoCount");
                require(p.renderingOptions() != null && p.renderingOptions().format() == OutputFormat.JPEG && Integer.valueOf(90).equals(p.renderingOptions().jpegQuality()), "renderingOptions.supported");
                require(p.layers() != null && p.layers().images() != null && p.layers().texts() != null, "layers.required");
                require(p.layers().images().size() == ids.size() && p.layers().texts().size() <= 1, "layers.count");
                Set<String> used = new HashSet<>();
                int order = 0;
                for (var image : p.layers().images()) {
                    require(image != null && ids.contains(image.photoId()) && used.add(image.photoId()), "image.selectedPhotoReference");
                    require(Integer.valueOf(order++).equals(image.order()), "image.layerOrder");
                    box(image.x(),image.y(),image.width(),image.height(),"image.destination");
                    require(image.rotation() != null && Set.of(0,90,180,270).contains(image.rotation()), "image.rotation");
                    require(image.fitMode()==ImageFitMode.COVER,"image.fitMode");
                    if(image.focalPoint()!=null) {
                        number(image.focalPoint().x(),0,1,"image.focalPoint.x");
                        number(image.focalPoint().y(),0,1,"image.focalPoint.y");
                    }
                    var crop = image.crop(); require(crop != null, "image.cropRequired"); box(crop.x(),crop.y(),crop.width(),crop.height(),"image.crop");
                    var a = sources.get(image.photoId());
                    double w = a.orientedWidth(), h = a.orientedHeight();
                    if (image.rotation() == 90 || image.rotation() == 270) { double swap = w; w = h; h = swap; }
                    double ratio = crop.width()*w/(crop.height()*h);
                    double target = image.width()*1080/(image.height()*1920);
                    if(finalized) require(Math.abs(ratio/target-1) <= CoverCropResolver.EPSILON, "image.cropDestinationAspectRatio");
                    var edits = image.adjustments(); require(edits != null, "image.adjustmentsRequired");
                    number(edits.exposure(),-1,1,"image.exposure"); number(edits.contrast(),.5,1.5,"image.contrast"); number(edits.saturation(),0,1.5,"image.saturation");
                }
                var images = p.layers().images();
                for (int i=0;i<images.size();i++) for(int j=i+1;j<images.size();j++) {
                    var a=images.get(i); var b=images.get(j);
                    require(Math.min(a.x()+a.width(),b.x()+b.width())-Math.max(a.x(),b.x()) <= 1e-7
                            || Math.min(a.y()+a.height(),b.y()+b.height())-Math.max(a.y(),b.y()) <= 1e-7, "images.overlap");
                }
                require(s.caption() != null && s.caption().include() != null && s.caption().text() != null, "caption.required");
                if (!s.caption().include()) { require(s.caption().text().isEmpty() && p.layers().texts().isEmpty(), "caption.disabledConsistency"); }
                else {
                    text(s.caption().text(),120,"caption.text"); require(p.layers().texts().size()==1, "caption.textLayerCount");
                    var t=p.layers().texts().getFirst(); require(t != null && s.caption().text().equals(t.text()), "caption.textMismatch");
                    require(Integer.valueOf(order).equals(t.order()) && t.font()==FontId.SANS_SERIF && t.alignment()!=null, "text.orderFontAlignment");
                    require(t.fontSize()!=null && t.fontSize()>=textSettings.minFontSize() && t.fontSize()<=textSettings.maxFontSize() && Integer.valueOf(0).equals(t.rotation()), "text.sizeOrRotation");
                    color(t.color(),"text.color"); box(t.x(),t.y(),t.width(),t.height(),"text.box");
                    require(t.y()>=.1 && t.y()+t.height()<=.9, "text.safeZone");
                    if(finalized) TextLayoutResolver.validateFinal(t,textSettings);
                }
            }
            return List.copyOf(result.recommendations());
        } catch (AiException e) { throw e; }
        catch (RuntimeException e) { throw new InvalidRecommendationException("plan.unexpectedStructure"); }
    }
}
