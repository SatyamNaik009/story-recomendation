package com.storystylist.backend.photo.service;

import com.storystylist.backend.photo.config.PhotoValidationProperties;
import com.storystylist.backend.photo.dto.PhotoInspectionResponse;
import com.storystylist.backend.photo.dto.PhotoMetadata;
import com.storystylist.backend.photo.exception.InvalidPhotoException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@EnableConfigurationProperties(PhotoValidationProperties.class)
public class PhotoValidationService {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private final PhotoValidationProperties limits;

    public PhotoValidationService(PhotoValidationProperties limits) {
        this.limits = limits;
    }

    public PhotoInspectionResponse inspect(List<MultipartFile> photos, String prompt) {
        if (photos == null || photos.isEmpty()) {
            throw new InvalidPhotoException("MISSING_PHOTOS", "At least one photo is required");
        }
        if (photos.size() > limits.maxPhotos()) {
            throw new InvalidPhotoException("TOO_MANY_PHOTOS", "Too many photos; maximum is " + limits.maxPhotos());
        }
        boolean promptProvided = prompt != null && !prompt.isBlank();
        if (promptProvided && prompt.codePointCount(0, prompt.length()) > limits.maxPromptLength()) {
            throw new InvalidPhotoException("INVALID_PROMPT", "Prompt exceeds " + limits.maxPromptLength() + " characters");
        }
        List<PhotoMetadata> metadata = new ArrayList<>(photos.size());
        for (int i = 0; i < photos.size(); i++) {
            metadata.add(inspectPhoto(photos.get(i), "photo-" + (i + 1)));
        }
        return new PhotoInspectionResponse("VALID", metadata.size(), promptProvided, metadata);
    }

    private PhotoMetadata inspectPhoto(MultipartFile photo, String photoId) {
        if (photo == null || photo.isEmpty()) {
            throw new InvalidPhotoException("EMPTY_PHOTO", "Each photo must contain image data");
        }
        if (photo.getSize() > limits.maxFileSizeBytes()) {
            throw new MaxUploadSizeExceededException(limits.maxFileSizeBytes());
        }
        // Process one upload at a time; no getBytes(), full image decode, or manual disk cache.
        try (var input = new BufferedInputStream(photo.getInputStream())) {
            input.mark(PNG_SIGNATURE.length);
            byte[] signature = input.readNBytes(PNG_SIGNATURE.length);
            input.reset();
            String format = detectFormat(signature);
            try (var imageInput = new MemoryCacheImageInputStream(input)) {
                var readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) {
                    throw invalidImage();
                }
                ImageReader reader = readers.next();
                try {
                    if (!format.equalsIgnoreCase(reader.getFormatName())) {
                        throw invalidImage();
                    }
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > limits.maxWidth() || height > limits.maxHeight()
                            || (long) width * height > limits.maxPixels()) {
                        throw new InvalidPhotoException("INVALID_DIMENSIONS", "Image dimensions exceed the allowed limits");
                    }
                    return new PhotoMetadata(photoId, "image/" + format, photo.getSize(), width, height);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            // Do not expose parser details, filenames, paths, or uploaded bytes.
            throw invalidImage();
        }
    }

    private String detectFormat(byte[] signature) {
        if (Arrays.equals(signature, PNG_SIGNATURE)) {
            return "png";
        }
        if (signature.length >= 3 && signature[0] == (byte) 0xff
                && signature[1] == (byte) 0xd8 && signature[2] == (byte) 0xff) {
            return "jpeg";
        }
        throw invalidImage();
    }

    private InvalidPhotoException invalidImage() {
        return new InvalidPhotoException("INVALID_IMAGE", "Only valid JPEG and PNG images are supported");
    }
}
