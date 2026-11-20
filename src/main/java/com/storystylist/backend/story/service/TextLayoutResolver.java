package com.storystylist.backend.story.service;
import com.storystylist.backend.story.dto.*;
import java.awt.Font;
import java.awt.font.*;
import java.text.*;
import java.util.*;
/** Headless Java2D measurement; no pixels, image files, or AI requests are generated. */
public final class TextLayoutResolver {
    public static final FontRenderContext CONTEXT=new FontRenderContext(null,true,true);
    public static final double PADDING=4, SPACING=1.1;
    private TextLayoutResolver() {}
    private static void require(boolean ok,String rule) { if(!ok) throw new InvalidRecommendationException(rule); }
    public static TextLayer resolve(TextLayer layer,TextLayoutProperties limits) {
        require(layer!=null && layer.font()==FontId.SANS_SERIF && layer.alignment()!=null,"text.fontOrAlignment");
        require(layer.text()!=null && !layer.text().isBlank() && layer.text().length()<=120,"text.content");
        require(layer.fontSize()!=null && layer.fontSize()>=limits.minFontSize() && layer.fontSize()<=limits.maxFontSize(),"text.fontSize");
        require(layer.width()!=null && layer.height()!=null && Double.isFinite(layer.width()) && Double.isFinite(layer.height())
                && layer.width()>0 && layer.width()<=1 && layer.height()>0 && layer.height()<=1,"text.boxDimensions");
        require(layer.x()!=null && layer.y()!=null && Double.isFinite(layer.x()) && Double.isFinite(layer.y())
                && layer.x()>=0 && layer.y()>=0 && layer.x()+layer.width()<=1.000000001 && layer.y()+layer.height()<=1.000000001,"text.boxBounds");
        require(layer.text().codePoints().noneMatch(c->(Character.isISOControl(c)&&c!='\n'&&c!='\r') || Character.getType(c)==Character.FORMAT),"text.unsupportedControl");
        for(int size=layer.fontSize();size>=limits.minFontSize();size--) {
            var layout=measure(layer,size);
            if(layout!=null) return new TextLayer(layer.text(),layer.order(),layer.x(),layer.y(),layer.width(),layer.height(),
                    layer.font(),size,layer.color(),layer.alignment(),layer.rotation(),layout);
        }
        throw new InvalidRecommendationException("text.fit");
    }
    private static ResolvedTextLayout measure(TextLayer layer,int size) {
        return measure(layer,size,null);
    }
    private static ResolvedTextLayout measure(TextLayer layer,int size,List<TextLayout> glyphLayouts) {
        var font=new Font(Font.SANS_SERIF,Font.PLAIN,size);
        String text=layer.text().replace("\r\n","\n").replace('\r','\n');
        require(font.canDisplayUpTo(text.replace("\n",""))==-1,"text.unsupportedGlyph");
        double available=layer.width()*1080-2*PADDING, height=layer.height()*1920-2*PADDING;
        if(available<=0 || height<=0) return null;
        var lines=new ArrayList<TextLine>(); double top=0;
        for(String paragraph:text.split("\n",-1)) {
            String measured=paragraph.isEmpty()?" ":paragraph;
            var attributed=new AttributedString(measured); attributed.addAttribute(TextAttribute.FONT,font);
            var breaker=new LineBreakMeasurer(attributed.getIterator(),BreakIterator.getLineInstance(Locale.ROOT),CONTEXT);
            while(breaker.getPosition()<measured.length()) {
                int start=breaker.getPosition();
                var layout=breaker.nextLayout((float)available);
                if(layout==null || breaker.getPosition()<=start) return null;
                var bounds=layout.getBounds();
                double left=Math.min(0,bounds.getMinX()), right=Math.max(layout.getAdvance(),bounds.getMaxX());
                if(paragraph.isEmpty()) { left=0;right=0; }
                double width=right-left;
                if(width>available+1e-7) return null;
                double ascent=Math.max(layout.getAscent(),-bounds.getMinY());
                double descent=Math.max(layout.getDescent(),bounds.getMaxY());
                double baseline=top+ascent;
                if(baseline+descent>height+1e-7) return null;
                double offset=switch(layer.alignment()) {case LEFT->0;case CENTER->(available-width)/2;case RIGHT->available-width;};
                lines.add(new TextLine(paragraph.isEmpty()?"":paragraph.substring(start,breaker.getPosition()),
                        PADDING+offset-left,PADDING+baseline,width));
                if(glyphLayouts!=null) glyphLayouts.add(layout);
                top=baseline+descent+layout.getLeading()+(ascent+descent)*(.1);
            }
        }
        return new ResolvedTextLayout("java2d-sans-serif-v1",PADDING,SPACING,List.copyOf(lines));
    }
    public static void validateFinal(TextLayer layer,TextLayoutProperties limits) {
        require(layer.resolvedLayout()!=null,"text.resolvedLayoutRequired");
        var verified=resolve(layer,limits);
        require(verified.fontSize().equals(layer.fontSize()) && verified.resolvedLayout().equals(layer.resolvedLayout()),"text.fit");
    }
    /** Replays the exact paragraph layout algorithm, preserving shaping context; never changes size or wrapping. */
    public static List<TextLayout> layoutsForRendering(TextLayer layer,TextLayoutProperties limits) {
        validateFinal(layer,limits);
        var glyphs=new ArrayList<TextLayout>();
        var measured=measure(layer,layer.fontSize(),glyphs);
        require(layer.resolvedLayout().equals(measured),"text.runtimeMetricsMismatch");
        return List.copyOf(glyphs);
    }
}
