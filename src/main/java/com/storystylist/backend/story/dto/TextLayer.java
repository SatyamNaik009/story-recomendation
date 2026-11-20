package com.storystylist.backend.story.dto;
public record TextLayer(String text, Integer order, Double x, Double y, Double width, Double height, FontId font,
                        Integer fontSize, String color, TextAlignment alignment, Integer rotation, ResolvedTextLayout resolvedLayout) {
    public TextLayer(String text,Integer order,Double x,Double y,Double width,Double height,FontId font,Integer fontSize,String color,TextAlignment alignment,Integer rotation) {
        this(text,order,x,y,width,height,font,fontSize,color,alignment,rotation,null);
    }
}
