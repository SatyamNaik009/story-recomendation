package com.storystylist.backend.render.model;
import com.storystylist.backend.story.dto.*;
public record CompiledImageLayer(int order,String photoId,PixelRectangle destination,PixelCrop sourceCrop,
                                 int rotation,ImageFitMode fitMode,ImageAdjustments adjustments) implements CompiledLayer {}
