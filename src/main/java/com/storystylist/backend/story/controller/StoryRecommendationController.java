package com.storystylist.backend.story.controller;
import com.storystylist.backend.story.dto.StoryRecommendationResponse;
import com.storystylist.backend.story.service.StoryRecommendationService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
@RestController
public class StoryRecommendationController {
    private final StoryRecommendationService service;
    public StoryRecommendationController(StoryRecommendationService service) { this.service=service; }
    @PostMapping(value="/api/v1/stories/recommend", consumes="multipart/form-data", produces="application/json")
    public StoryRecommendationResponse recommend(@RequestParam(value="photos",required=false) List<MultipartFile> photos,
                                                  @RequestParam(value="prompt",required=false) String prompt) {
        return service.recommend(photos,prompt);
    }
}
