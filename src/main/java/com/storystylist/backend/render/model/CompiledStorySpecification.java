package com.storystylist.backend.render.model;
import com.storystylist.backend.story.dto.*;
import java.util.List;
public record CompiledStorySpecification(String specificationVersion,String recommendationId,StoryCanvas canvas,
        RenderOutputOptions output,StoryBackground background,List<CompiledLayer> layers,List<PhotoAssetMetadata> assets) {
    public CompiledStorySpecification { layers=List.copyOf(layers); assets=List.copyOf(assets); }
}
