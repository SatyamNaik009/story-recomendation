package com.storystylist.backend.render.model;
import com.storystylist.backend.story.dto.*;
import java.util.List;
public record CompiledTextLayer(int order,String text,TextPixelBox box,FontId font,int fontSize,
                                String color,TextAlignment alignment,int rotation,ResolvedTextLayout layout) implements CompiledLayer {
    public CompiledTextLayer {
        if(layout!=null) layout=new ResolvedTextLayout(layout.measurementVersion(),layout.paddingPixels(),layout.lineSpacing(),List.copyOf(layout.lines()));
    }
}
