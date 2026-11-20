package com.storystylist.backend.photo.service;

import com.storystylist.backend.photo.ProcessingTestImages;
import com.storystylist.backend.photo.TestImages;
import com.storystylist.backend.photo.config.PhotoProcessingProperties;
import com.storystylist.backend.photo.config.PhotoValidationProperties;
import com.storystylist.backend.photo.dto.ProcessedPhoto;
import com.storystylist.backend.photo.exception.InvalidPhotoException;
import net.coobird.thumbnailator.util.exif.ExifUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PhotoPreprocessingServiceTests {
    private final PhotoPreprocessingService service = service(1568, 1568, 0.85);

    private PhotoPreprocessingService service(int width, int height, double quality) {
        return new PhotoPreprocessingService(new PhotoValidationService(
                new PhotoValidationProperties(10, 10485760, 300, 25_000_000, 10_000, 10_000)),
                new PhotoProcessingProperties(width, height, quality, "jpeg", false, true));
    }

    @ParameterizedTest
    @CsvSource({"4000,3000,1568,1176", "3000,4000,1176,1568", "2000,2000,1568,1568",
            "800,600,800,600", "1001,667,1001,667", "1,10,1,10"})
    void resizesWithoutCroppingOrUpscaling(int width, int height, int expectedWidth, int expectedHeight) throws IOException {
        var result = process(TestImages.image("jpeg", width, height));
        var metadata = result.metadata();
        assertThat(metadata.originalWidth()).isEqualTo(width);
        assertThat(metadata.originalHeight()).isEqualTo(height);
        assertThat(metadata.processedWidth()).isEqualTo(expectedWidth);
        assertThat(metadata.processedHeight()).isEqualTo(expectedHeight);
        assertThat(metadata.processedWidth()).isLessThanOrEqualTo(width);
        assertThat(metadata.processedHeight()).isLessThanOrEqualTo(height);
        assertThat((double) expectedWidth / expectedHeight).isCloseTo((double) width / height, within(0.002));
        assertThat(metadata.processedMediaType()).isEqualTo("image/jpeg");
        assertThat(metadata.processedSizeBytes()).isEqualTo(result.bytes().length).isPositive();
        assertThat(metadata.orientationCorrected()).isFalse();
        assertJpeg(result, expectedWidth, expectedHeight);
    }

    @Test
    void oddDimensionsUseOriginalAspectRatioAfterSubsampling() throws IOException {
        var results = service(234, 200, 0.85).preprocess(List.of(file(TestImages.image("png", 1001, 667))), null);
        assertJpeg(results.getFirst(), 234, 156);
    }

    @ParameterizedTest
    @CsvSource({"1,0,1,2,3", "2,1,0,3,2", "3,3,2,1,0", "4,2,3,0,1",
            "5,0,2,1,3", "6,2,0,3,1", "7,3,1,2,0", "8,1,3,0,2"})
    void correctsAllExifOrientationsFromPixels(int orientation, int tl, int tr, int bl, int br) throws IOException {
        var result = process(ProcessingTestImages.orientedJpeg(120, 80, orientation));
        int width = orientation >= 5 ? 80 : 120;
        int height = orientation >= 5 ? 120 : 80;
        assertThat(result.metadata().orientationCorrected()).isEqualTo(orientation != 1);
        assertThat(result.metadata().originalWidth()).isEqualTo(120);
        assertThat(result.metadata().originalHeight()).isEqualTo(80);
        var image = assertJpeg(result, width, height);
        assertCorners(image, tl, tr, bl, br);
    }

    @Test
    void usesCorrectedDisplayDimensionsForAsymmetricBounds() throws IOException {
        var result = service(300, 150, 0.85).preprocess(
                List.of(file(ProcessingTestImages.orientedJpeg(1200, 800, 6))), null).getFirst();
        assertCorners(assertJpeg(result, 100, 150), 2, 0, 3, 1);
    }

    @Test
    void outputHasNoExifAndReprocessingDoesNotRotateAgain() throws IOException {
        var first = process(ProcessingTestImages.orientedJpeg(120, 80, 6));
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(first.bytes()))) {
            var reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input);
                assertThat(ExifUtils.getExifOrientation(reader, 0)).isNull();
            } finally {
                reader.dispose();
            }
        }
        assertThat(new String(first.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        var second = process(first.bytes());
        assertThat(second.metadata().orientationCorrected()).isFalse();
        assertCorners(assertJpeg(second, 80, 120), 2, 0, 3, 1);
    }

    @Test
    void malformedExifFallsBackToStoredPixelsWithoutClaimingCorrection() throws IOException {
        var image = ProcessingTestImages.withExif(ProcessingTestImages.pattern(120, 80),
                new byte[]{'E', 'x', 'i', 'f', 0, 0, 1});
        var result = process(image);
        assertThat(result.metadata().orientationCorrected()).isFalse();
        assertCorners(assertJpeg(result, 120, 80), 0, 1, 2, 3);
    }

    @Test
    void unknownExifValueFallsBackWithoutCorrection() throws IOException {
        var result = process(ProcessingTestImages.orientedJpeg(120, 80, 99));
        assertThat(result.metadata().orientationCorrected()).isFalse();
        assertCorners(assertJpeg(result, 120, 80), 0, 1, 2, 3);
    }

    @Test
    void readsBigEndianExifWithNonDefaultIfdOffset() throws IOException {
        var data = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN);
        data.put(new byte[]{'E', 'x', 'i', 'f', 0, 0, 'M', 'M'}).putShort((short) 42).putInt(16);
        data.position(22);
        data.putShort((short) 1).putShort((short) 0x0112).putShort((short) 3).putInt(1)
                .putShort((short) 6).putShort((short) 0).putInt(0);
        var result = process(ProcessingTestImages.withExif(ProcessingTestImages.pattern(120, 80), data.array()));
        assertThat(result.metadata().orientationCorrected()).isTrue();
        assertCorners(assertJpeg(result, 80, 120), 2, 0, 3, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"byteOrder", "magic", "offset", "type", "count"})
    void invalidExifStructureNeverClaimsCorrection(String corruption) throws IOException {
        byte[] exif = ProcessingTestImages.exif(6);
        var data = ByteBuffer.wrap(exif).order(ByteOrder.LITTLE_ENDIAN);
        switch (corruption) {
            case "byteOrder" -> exif[6] = 'Z';
            case "magic" -> data.putShort(8, (short) 0);
            case "offset" -> data.putInt(10, Integer.MAX_VALUE);
            case "type" -> data.putShort(18, (short) 4);
            case "count" -> data.putInt(20, 2);
            default -> throw new AssertionError("Unknown fixture");
        }
        var result = process(ProcessingTestImages.withExif(ProcessingTestImages.pattern(120, 80), exif));
        assertThat(result.metadata().orientationCorrected()).isFalse();
        assertCorners(assertJpeg(result, 120, 80), 0, 1, 2, 3);
    }

    @Test
    void transparencyIsCompositedOntoWhite() throws IOException {
        var result = process(ProcessingTestImages.transparentPng());
        var image = assertJpeg(result, 120, 80);
        assertColor(new Color(image.getRGB(20, 40)), Color.WHITE);
        assertColor(new Color(image.getRGB(100, 40)), new Color(255, 127, 127));
        assertThat(image.getColorModel().hasAlpha()).isFalse();
    }

    @Test
    void appliesConfiguredJpegQualityToActualEncoding() throws IOException {
        var source = file(ProcessingTestImages.noisyPng());
        var low = service(1568, 1568, 0.2).preprocess(List.of(source), null).getFirst();
        var high = service(1568, 1568, 0.95).preprocess(List.of(source), null).getFirst();
        assertThat(high.bytes().length).isGreaterThan(low.bytes().length);
        assertThat(high.bytes()).isNotEqualTo(low.bytes());
        assertJpeg(low, 320, 240);
        assertJpeg(high, 320, 240);
    }

    @Test
    void preservesIdsUploadOrderAndOriginalBytes() throws IOException {
        byte[] original = ProcessingTestImages.orientedJpeg(120, 80, 8);
        byte[] snapshot = original.clone();
        var first = file(original);
        var second = file(TestImages.image("png", 30, 20));
        var results = service.preprocess(List.of(first, second), "Summer");
        assertThat(results).extracting(p -> p.metadata().photoId()).containsExactly("photo-1", "photo-2");
        assertThat(results).extracting(p -> p.metadata().processedWidth()).containsExactly(80, 30);
        assertThat(original).isEqualTo(snapshot);
        assertThat(first.getBytes()).isEqualTo(snapshot);
        assertThat(results.getFirst().bytes()).isNotSameAs(original);
        assertThat(new ObjectMapper().valueToTree(results.getFirst()).has("bytes")).isFalse();
    }

    @Test
    void handlesTenImagesAndReusesAllExistingValidation() throws IOException {
        var photo = file(TestImages.image("png", 30, 20));
        assertThat(service.preprocess(Collections.nCopies(10, photo), " ")).hasSize(10);
        assertInvalid(() -> service.preprocess(Collections.nCopies(11, photo), null), "TOO_MANY_PHOTOS");
        assertInvalid(() -> service.preprocess(List.of(photo), "a".repeat(301)), "INVALID_PROMPT");
        assertInvalid(() -> service.preprocess(null, null), "MISSING_PHOTOS");
        assertInvalid(() -> service.preprocess(List.of(file(new byte[]{1, 2, 3})), null), "INVALID_IMAGE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void rejectsCorruptedPayloadThatPassesHeaderInspection(String format) throws IOException {
        byte[] valid = TestImages.image(format, 100, 80);
        byte[] damaged = Arrays.copyOf(valid, format.equals("png") ? 45 : valid.length - 20);
        var photo = file(damaged);
        var validator = new PhotoValidationService(new PhotoValidationProperties(10, 10485760, 300, 25000000, 10000, 10000));
        assertThat(validator.inspect(List.of(photo), null).status()).isEqualTo("VALID");
        assertInvalid(() -> service.preprocess(List.of(photo), null), "INVALID_IMAGE");
    }

    @Test
    void closesUploadStreamsAndDoesNotOpenMultipleImagesConcurrently() throws IOException {
        int[] active = {0};
        int[] peak = {0};
        byte[] bytes = TestImages.image("png", 30, 20);
        var photo = new MockMultipartFile("photos", bytes) {
            @Override public InputStream getInputStream() {
                active[0]++;
                peak[0] = Math.max(peak[0], active[0]);
                return new ByteArrayInputStream(bytes) {
                    private boolean closed;
                    @Override public void close() {
                        if (!closed) { active[0]--; closed = true; }
                    }
                };
            }
        };
        service.preprocess(List.of(photo, photo), null);
        assertThat(active[0]).isZero();
        assertThat(peak[0]).isEqualTo(1);
    }

    private ProcessedPhoto process(byte[] bytes) {
        return service.preprocess(List.of(file(bytes)), null).getFirst();
    }

    private MultipartFile file(byte[] bytes) {
        return new MockMultipartFile("photos", "../../untrusted.bin", "application/octet-stream", bytes);
    }

    private BufferedImage assertJpeg(ProcessedPhoto photo, int width, int height) throws IOException {
        assertThat(photo.bytes()).startsWith((byte) 0xff, (byte) 0xd8, (byte) 0xff);
        var decoded = ImageIO.read(new ByteArrayInputStream(photo.bytes()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(width).isEqualTo(photo.metadata().processedWidth());
        assertThat(decoded.getHeight()).isEqualTo(height).isEqualTo(photo.metadata().processedHeight());
        return decoded;
    }

    private void assertCorners(BufferedImage image, int... colors) {
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                Color actual = new Color(image.getRGB((2 * x + 1) * image.getWidth() / 4,
                        (2 * y + 1) * image.getHeight() / 4));
                assertColor(actual, ProcessingTestImages.COLORS[colors[y * 2 + x]]);
            }
        }
    }

    private void assertColor(Color actual, Color expected) {
        assertThat(actual.getRed()).isCloseTo(expected.getRed(), within(20));
        assertThat(actual.getGreen()).isCloseTo(expected.getGreen(), within(20));
        assertThat(actual.getBlue()).isCloseTo(expected.getBlue(), within(20));
    }

    private void assertInvalid(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InvalidPhotoException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
