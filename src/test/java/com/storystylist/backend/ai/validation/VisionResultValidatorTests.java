package com.storystylist.backend.ai.validation;
import com.storystylist.backend.ai.domain.*;
import com.storystylist.backend.ai.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Collections;
import java.util.List;
import static com.storystylist.backend.ai.AiTestData.*;
import static org.assertj.core.api.Assertions.*;
class VisionResultValidatorTests {
    private final VisionResultValidator validator = new VisionResultValidator();
    private final VisionResponseCodec codec = new VisionResponseCodec();
    @Test void restoresInputOrdering() throws Exception {
        var result = validator.validate(analysis(List.of("photo-2", "photo-1")), photos());
        assertThat(result.photos()).extracting(PhotoObservation::photoId).containsExactly("photo-1", "photo-2");
    }
    @Test void rejectsUnknownDuplicateMissingAndComplementaryReferences() throws Exception {
        for (var result : List.of(analysis(List.of("photo-1")), analysis(List.of("photo-1", "photo-1")),
                analysis(List.of("photo-1", "photo-3")),
                new VisionAnalysisResult("Calm", analysis(List.of("photo-1", "photo-2")).photos(),
                        new GroupAnalysis("Theme", List.of("photo-3"), "Observation")),
                new VisionAnalysisResult("Calm", analysis(List.of("photo-1", "photo-2")).photos(),
                        new GroupAnalysis("Theme", List.of("photo-1", "photo-1"), "Observation")))) {
            assertInvalid(result);
        }
    }
    @Test void rejectsMissingFieldsBlankDescriptionsAndUnboundedCollections() throws Exception {
        var second = observation("photo-2");
        for (var first : List.of(
                new PhotoObservation("photo-1", "", "Light", "Composition", List.of(), List.of()),
                new PhotoObservation("photo-1", null, "Light", "Composition", List.of(), List.of()),
                new PhotoObservation("photo-1", "a".repeat(2001), "Light", "Composition", List.of(), List.of()),
                new PhotoObservation("photo-1", "Description", null, "Composition", List.of(), List.of()),
                new PhotoObservation("photo-1", "Description", "Light", "Composition", null, List.of()),
                new PhotoObservation("photo-1", "Description", "Light", "Composition", Collections.nCopies(11, "strength"), List.of()))) {
            assertInvalid(new VisionAnalysisResult("Calm", List.of(first, second), new GroupAnalysis("Theme", List.of(), "Observation")));
        }
        assertInvalid(new VisionAnalysisResult("Calm", null, null));
        assertInvalid(new VisionAnalysisResult("Calm", List.of(observation("photo-1"), second), null));
        assertInvalid(new VisionAnalysisResult(null, List.of(observation("photo-1"), second), new GroupAnalysis("Theme", List.of(), "Observation")));
    }
    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{}", "[]", "{\"overallMood\":12}", "{\"unexpected\":true}"})
    void malformedOrIncompleteJsonNeverBecomesSuccessfulAnalysis(String raw) {
        assertThatThrownBy(() -> validator.validate(codec.parse(raw), photos())).isInstanceOf(AiException.class);
    }
    @Test void rejectsDuplicateKeysTrailingJsonAndExcessiveOutput() {
        for (String raw : List.of(json() + " {}", json().replace("\"overallMood\":", "\"overallMood\":\"other\",\"overallMood\":"),
                " ".repeat(VisionResponseCodec.MAX_RESPONSE_CHARACTERS + 1))) {
            assertThatThrownBy(() -> codec.parse(raw)).isInstanceOf(AiException.class);
        }
    }

    @Test void rejectsNumericTextEvenWhenAllOtherFieldsAreValid() {
        String raw = json().replace("\"overallMood\":\"Calm\"", "\"overallMood\":123");
        assertThatThrownBy(() -> codec.parse(raw)).isInstanceOf(AiException.class);
    }
    private void assertInvalid(VisionAnalysisResult result) throws Exception {
        assertThatThrownBy(() -> validator.validate(result, photos())).isInstanceOfSatisfying(AiException.class,
                e -> assertThat(e.error()).isEqualTo(AiError.AI_INVALID_RESPONSE));
    }
}
