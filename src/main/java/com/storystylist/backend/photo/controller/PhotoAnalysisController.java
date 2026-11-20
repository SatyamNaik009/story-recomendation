package com.storystylist.backend.photo.controller;
import com.storystylist.backend.photo.dto.PhotoAnalysisResponse;
import com.storystylist.backend.photo.service.PhotoAnalysisService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
@RestController
public class PhotoAnalysisController {
    private final PhotoAnalysisService analysis;
    public PhotoAnalysisController(PhotoAnalysisService analysis) { this.analysis = analysis; }
    @PostMapping(value = "/api/v1/photos/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PhotoAnalysisResponse analyze(@RequestParam(value = "photos", required = false) List<MultipartFile> photos,
                                         @RequestParam(value = "prompt", required = false) String prompt) {
        return analysis.analyze(photos, prompt);
    }
}
