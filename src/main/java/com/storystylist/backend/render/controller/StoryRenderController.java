package com.storystylist.backend.render.controller;
import com.storystylist.backend.render.service.StoryRenderer;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import java.util.List;
@RestController
public class StoryRenderController {
    private final StoryRenderer renderer;
    private final com.storystylist.backend.storage.StoredStoryRenderer stored;
    public StoryRenderController(StoryRenderer renderer,com.storystylist.backend.storage.StoredStoryRenderer stored){this.renderer=renderer;this.stored=stored;}
    public record RenderByIdRequest(String recommendationId){}
    @PostMapping(value="/api/v1/stories/render",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> renderStored(@RequestBody(required=false) RenderByIdRequest request) {
        return png(stored.render(request==null?null:request.recommendationId()));
    }
    @PostMapping(value="/api/v1/stories/render",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> render(@RequestParam(value="renderPlan",required=false) String plan,
            @RequestParam(value="assets",required=false) String assets,@RequestParam(value="recommendationId",required=false) String id,
            @RequestParam(value="photos",required=false) List<MultipartFile> photos) {
        var png=renderer.render(plan,assets,id,photos);
        return png(png);
    }
    private ResponseEntity<byte[]> png(byte[] png) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(png.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\"story.png\"")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(png);
    }
}
