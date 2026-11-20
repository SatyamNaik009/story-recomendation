package com.storystylist.backend.photo.controller;

import com.storystylist.backend.photo.dto.PhotoPreprocessingResponse;
import com.storystylist.backend.photo.dto.ProcessedPhoto;
import com.storystylist.backend.photo.service.PhotoPreprocessingService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
public class PhotoPreprocessingController {
    private final PhotoPreprocessingService preprocessing;

    public PhotoPreprocessingController(PhotoPreprocessingService preprocessing) {
        this.preprocessing = preprocessing;
    }

    @PostMapping(value = "/api/v1/photos/preprocess", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PhotoPreprocessingResponse preprocess(
            @RequestParam(value = "photos", required = false) List<MultipartFile> photos,
            @RequestParam(value = "prompt", required = false) String prompt) {
        var results = preprocessing.preprocess(photos, prompt);
        return new PhotoPreprocessingResponse("PROCESSED", results.size(),
                results.stream().map(ProcessedPhoto::metadata).toList());
    }
}
