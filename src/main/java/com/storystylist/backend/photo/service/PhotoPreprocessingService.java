package com.storystylist.backend.photo.service;

import com.storystylist.backend.photo.config.PhotoProcessingProperties;
import com.storystylist.backend.photo.dto.PhotoMetadata;
import com.storystylist.backend.photo.dto.PhotoPreprocessingMetadata;
import com.storystylist.backend.photo.dto.ProcessedPhoto;
import com.storystylist.backend.photo.exception.InvalidPhotoException;
import com.storystylist.backend.photo.exception.PhotoProcessingException;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.util.exif.ExifFilterUtils;
import net.coobird.thumbnailator.util.exif.Orientation;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
@EnableConfigurationProperties(PhotoProcessingProperties.class)
public class PhotoPreprocessingService {
    private final PhotoValidationService validation;
    private final PhotoProcessingProperties settings;

    public PhotoPreprocessingService(PhotoValidationService validation, PhotoProcessingProperties settings) {
        this.validation = validation;
        this.settings = settings;
    }

    /** Validates once, then processes sequentially; original multipart content is never modified. */
    public List<ProcessedPhoto> preprocess(List<MultipartFile> photos, String prompt) {
        var inspection = validation.inspect(photos, prompt);
        List<ProcessedPhoto> results = new ArrayList<>(inspection.photoCount());
        for (int i = 0; i < inspection.photoCount(); i++) {
            try (InputStream source = photos.get(i).getInputStream()) {
                results.add(process(source, inspection.photos().get(i)));
            } catch (IOException exception) {
                throw invalidImage();
            }
        }
        return List.copyOf(results);
    }

    private ProcessedPhoto process(InputStream source, PhotoMetadata original) throws IOException {
        BufferedImage decoded = null;
        BufferedImage oriented = null;
        BufferedImage resized = null;
        BufferedImage rgb = null;
        try (var input = new MemoryCacheImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            ImageReader reader = readers.next();
            Orientation orientation;
            int targetWidth;
            int targetHeight;
            try {
                reader.setInput(input, false, false);
                // Ensure the decoder is still reading precisely the validated source before allocating pixels.
                if (reader.getWidth(0) != original.width() || reader.getHeight(0) != original.height()
                        || !("image/" + reader.getFormatName()).equalsIgnoreCase(original.mediaType())) {
                    throw invalidImage();
                }
                orientation = "image/jpeg".equals(original.mediaType()) ? JpegExifOrientationReader.read(reader) : null;
                boolean swap = swapsAxes(orientation);
                int displayWidth = swap ? original.height() : original.width();
                int displayHeight = swap ? original.width() : original.height();
                double scale = Math.min(1.0, Math.min((double) settings.maxWidth() / displayWidth,
                        (double) settings.maxHeight() / displayHeight));
                targetWidth = Math.max(1, (int) Math.round(displayWidth * scale));
                targetHeight = Math.max(1, (int) Math.round(displayHeight * scale));

                // Retain at least the final target resolution, then use high-quality resizing.
                int sample = Math.max(1, Math.min(displayWidth / targetWidth, displayHeight / targetHeight));
                ImageReadParam readParam = reader.getDefaultReadParam();
                readParam.setSourceSubsampling(sample, sample, 0, 0);
                boolean[] warning = {false};
                reader.addIIOReadWarningListener((ignored, message) -> warning[0] = true);
                decoded = reader.read(0, readParam);
                // ImageIO may otherwise silently repair a truncated JPEG.
                if (decoded == null || warning[0]) {
                    throw invalidImage();
                }
            } catch (IOException | RuntimeException exception) {
                throw invalidImage();
            } finally {
                reader.dispose();
            }

            try {
                boolean corrected = orientation != null && orientation != Orientation.TOP_LEFT;
                oriented = corrected ? ExifFilterUtils.getFilterForOrientation(orientation).apply(decoded) : decoded;
                // EXIF is applied exactly once above. Dimensions derive from the original display ratio,
                // not the rounded subsampled raster; forceSize only resolves that integer rounding difference.
                resized = oriented.getWidth() == targetWidth && oriented.getHeight() == targetHeight ? oriented
                        : Thumbnails.of(oriented).forceSize(targetWidth, targetHeight)
                                .useExifOrientation(false).asBufferedImage();
                // Always create an RGB raster: flatten alpha onto white and discard all source metadata.
                rgb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
                var graphics = rgb.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, targetWidth, targetHeight);
                    graphics.drawImage(resized, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                byte[] bytes = encodeJpeg(rgb);
                var metadata = new PhotoPreprocessingMetadata(original.photoId(), original.width(), original.height(),
                        rgb.getWidth(), rgb.getHeight(), "image/jpeg", bytes.length, corrected);
                return new ProcessedPhoto(metadata, bytes);
            } catch (IOException | RuntimeException exception) {
                throw new PhotoProcessingException(exception);
            }
        } finally {
            if (rgb != null) rgb.flush();
            if (resized != null && resized != oriented) resized.flush();
            if (oriented != null && oriented != decoded) oriented.flush();
            if (decoded != null) decoded.flush();
        }
    }

    private boolean swapsAxes(Orientation orientation) {
        return orientation == Orientation.LEFT_TOP || orientation == Orientation.RIGHT_TOP
                || orientation == Orientation.RIGHT_BOTTOM || orientation == Orientation.LEFT_BOTTOM;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName(settings.outputFormat());
        if (!writers.hasNext()) {
            throw new IllegalStateException("JPEG writer unavailable");
        }
        var writer = writers.next();
        try (var bytes = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality((float) settings.jpegQuality());
            writer.write(null, new IIOImage(image, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private InvalidPhotoException invalidImage() {
        return new InvalidPhotoException("INVALID_IMAGE", "Image data could not be decoded safely");
    }
}
