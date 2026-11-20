package com.storystylist.backend.photo.service;

import com.storystylist.backend.photo.config.PhotoValidationProperties;
import com.storystylist.backend.photo.exception.InvalidPhotoException;
import com.storystylist.backend.photo.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoValidationServiceTests {
    private final PhotoValidationService service = new PhotoValidationService(
            new PhotoValidationProperties(10, 10 * 1024 * 1024, 300, 25_000_000, 10_000, 10_000));

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void validImagesReturnActualMetadata(String format) throws IOException {
        var photo = TestImages.photo(format, 30, 20);
        var result = service.inspect(List.of(photo), "Summer");
        assertThat(result.status()).isEqualTo("VALID");
        assertThat(result.photoCount()).isEqualTo(1);
        assertThat(result.promptProvided()).isTrue();
        var metadata = result.photos().getFirst();
        assertThat(metadata.photoId()).isEqualTo("photo-1");
        assertThat(metadata.mediaType()).isEqualTo("image/" + format);
        assertThat(metadata.sizeBytes()).isEqualTo(photo.getSize());
        assertThat(metadata.width()).isEqualTo(30);
        assertThat(metadata.height()).isEqualTo(20);
    }

    @Test
    void preservesOrderingAndAssignsDistinctIdsEvenWithDuplicateNames() throws IOException {
        var first = TestImages.photo("png", 12, 34);
        var second = TestImages.photo("png", 56, 78);
        var result = service.inspect(List.of(first, second), null);
        assertThat(result.photoCount()).isEqualTo(2);
        assertThat(result.photos()).extracting("photoId").containsExactly("photo-1", "photo-2");
        assertThat(result.photos()).extracting("width").containsExactly(12, 56);
        assertThat(result.photos()).extracting("height").containsExactly(34, 78);
    }

    @Test
    void acceptsExactlyTenPhotos() throws IOException {
        var result = service.inspect(Collections.nCopies(10, TestImages.photo("png", 2, 3)), null);
        assertThat(result.photoCount()).isEqualTo(10);
        assertThat(result.photos().getLast().photoId()).isEqualTo("photo-10");
        assertThat(result.photos().stream().map(p -> p.photoId()).distinct()).hasSize(10);
    }

    @Test
    void rejectsElevenPhotos() throws IOException {
        var photos = Collections.<MultipartFile>nCopies(11, TestImages.photo("png", 2, 3));
        assertInvalid(photos, null, "TOO_MANY_PHOTOS");
    }

    @Test
    void rejectsMissingAndEmptyCollections() {
        assertInvalid(null, null, "MISSING_PHOTOS");
        assertInvalid(List.of(), null, "MISSING_PHOTOS");
    }

    @Test
    void rejectsEmptyPhoto() {
        assertInvalid(List.of(new MockMultipartFile("photos", new byte[0])), null, "EMPTY_PHOTO");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello world", "%PDF-1.7"})
    void rejectsUnsupportedContentDespiteJpegExtensionAndMime(String content) {
        var file = new MockMultipartFile("photos", "spoof.jpg", "image/jpeg", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertInvalid(List.of(file), null, "INVALID_IMAGE");
    }

    @Test
    void ignoresUntrustedMimeAndFilenameWhenContentIsValid() throws IOException {
        var file = new MockMultipartFile("photos", "../../secret.exe", "application/pdf", TestImages.image("png", 5, 7));
        assertThat(service.inspect(List.of(file), null).photos().getFirst().mediaType()).isEqualTo("image/png");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void rejectsCorruptedSignatures(String format) throws IOException {
        byte[] image = TestImages.image(format, 5, 7);
        image[0] = 0;
        assertInvalid(List.of(new MockMultipartFile("photos", image)), null, "INVALID_IMAGE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void rejectsUnreadableMetadataAfterValidSignature(String format) throws IOException {
        byte[] signatureOnly = Arrays.copyOf(TestImages.image(format, 5, 7), 8);
        assertInvalid(List.of(new MockMultipartFile("photos", signatureOnly)), null, "INVALID_IMAGE");
    }

    @Test
    void rejectsExcessivePixelCountWithoutDecoding() throws IOException {
        assertInvalid(List.of(pngWithDimensions(5001, 5000)), null, "INVALID_DIMENSIONS");
    }

    @Test
    void rejectsIndividualDimensionsAndOverflowSizedDimensions() throws IOException {
        assertInvalid(List.of(pngWithDimensions(10001, 1)), null, "INVALID_DIMENSIONS");
        assertInvalid(List.of(pngWithDimensions(1, 10001)), null, "INVALID_DIMENSIONS");
        assertInvalid(List.of(pngWithDimensions(Integer.MAX_VALUE, Integer.MAX_VALUE)), null, "INVALID_DIMENSIONS");
    }

    @Test
    void configurablePixelLimitIsInclusive() throws IOException {
        var limited = new PhotoValidationService(new PhotoValidationProperties(10, 10485760, 300, 6, 10, 10));
        assertThat(limited.inspect(List.of(TestImages.photo("png", 2, 3)), null).photoCount()).isEqualTo(1);
        var tooBig = TestImages.photo("png", 3, 3);
        assertThatThrownBy(() -> limited.inspect(List.of(tooBig), null)).isInstanceOf(InvalidPhotoException.class);
    }

    @Test
    void rejectsOversizedFileBeforeOpeningStream() {
        var photo = new MockMultipartFile("photos", new byte[]{1}) {
            @Override public long getSize() { return 10485761; }
            @Override public InputStream getInputStream() { throw new AssertionError("Must reject before reading"); }
        };
        assertThatThrownBy(() -> service.inspect(List.of(photo), null)).isInstanceOf(MaxUploadSizeExceededException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void acceptsAbsentAndBlankPrompts(String prompt) throws IOException {
        assertThat(service.inspect(List.of(TestImages.photo("png", 2, 3)), prompt).promptProvided()).isFalse();
    }

    @Test
    void promptLimitCountsUnicodeCharactersAndAcceptsBoundary() throws IOException {
        var photos = List.<MultipartFile>of(TestImages.photo("png", 2, 3));
        assertThat(service.inspect(photos, "a".repeat(300)).promptProvided()).isTrue();
        assertThat(service.inspect(photos, "\uD83D\uDE00".repeat(300)).promptProvided()).isTrue();
        assertInvalid(photos, "a".repeat(301), "INVALID_PROMPT");
        assertInvalid(photos, "\uD83D\uDE00".repeat(301), "INVALID_PROMPT");
        assertThat(service.inspect(photos, " ".repeat(301)).promptProvided()).isFalse();
    }

    @Test
    void closesStreamsOnSuccessAndFailure() throws IOException {
        for (byte[] bytes : List.of(TestImages.image("png", 2, 3), new byte[]{1, 2, 3})) {
            boolean[] closed = {false};
            var photo = new MockMultipartFile("photos", bytes) {
                @Override public InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes) {
                        @Override public void close() { closed[0] = true; }
                    };
                }
            };
            try {
                service.inspect(List.of(photo), null);
            } catch (InvalidPhotoException ignored) {
                // Both parser success and rejection must close the upload stream.
            }
            assertThat(closed[0]).isTrue();
        }
    }

    private MockMultipartFile pngWithDimensions(int width, int height) throws IOException {
        byte[] bytes = TestImages.image("png", 1, 1);
        ByteBuffer.wrap(bytes).putInt(16, width).putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        return new MockMultipartFile("photos", bytes);
    }

    private void assertInvalid(List<MultipartFile> photos, String prompt, String code) {
        assertThatThrownBy(() -> service.inspect(photos, prompt)).isInstanceOfSatisfying(
                InvalidPhotoException.class, exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
