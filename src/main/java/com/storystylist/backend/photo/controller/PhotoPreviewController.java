package com.storystylist.backend.photo.controller;

import com.storystylist.backend.photo.exception.InvalidPhotoException;
import com.storystylist.backend.photo.service.PhotoPreprocessingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@ConditionalOnProperty(prefix = "story.photo.processing", name = "preview-enabled", havingValue = "true")
public class PhotoPreviewController {
    private final PhotoPreprocessingService preprocessing;

    public PhotoPreviewController(PhotoPreprocessingService preprocessing) {
        this.preprocessing = preprocessing;
    }

    @PostMapping(value = "/api/v1/photos/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> preview(@RequestParam(value = "photo", required = false) List<MultipartFile> photos) {
        if (photos != null && photos.size() > 1) {
            throw new InvalidPhotoException("INVALID_PHOTO_COUNT", "Preview accepts exactly one photo");
        }
        var photo = preprocessing.preprocess(photos, null).getFirst();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).contentLength(photo.bytes().length)
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.jpg\"").body(photo.bytes());
    }
}
