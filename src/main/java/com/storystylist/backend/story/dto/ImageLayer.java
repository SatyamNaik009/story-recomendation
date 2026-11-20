package com.storystylist.backend.story.dto;
public record ImageLayer(String photoId, Integer order, Double x, Double y, Double width, Double height,
                         ImageCrop crop, Integer rotation, ImageAdjustments adjustments,
                         ImageFitMode fitMode, FocalPoint focalPoint) {
    public ImageLayer(String photoId, Integer order, Double x, Double y, Double width, Double height,
                      ImageCrop crop, Integer rotation, ImageAdjustments adjustments) {
        this(photoId,order,x,y,width,height,crop,rotation,adjustments,ImageFitMode.COVER,null);
    }
}
