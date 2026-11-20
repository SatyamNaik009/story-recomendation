package com.storystylist.backend.photo.controller;

import com.storystylist.backend.photo.dto.PhotoInspectionResponse;
import com.storystylist.backend.photo.service.PhotoValidationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
public class PhotoUploadController {
    private final PhotoValidationService validationService;

    public PhotoUploadController(PhotoValidationService validationService) {
        this.validationService = validationService;
    }

    @PostMapping(value = "/api/v1/photos/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PhotoInspectionResponse inspect(
            @RequestParam(value = "photos", required = false) List<MultipartFile> photos,
            @RequestParam(value = "prompt", required = false) String prompt) {
        return validationService.inspect(photos, prompt);
    }
}
