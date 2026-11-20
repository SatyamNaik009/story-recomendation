package com.storystylist.backend.story;
import com.storystylist.backend.story.controller.*;
import com.storystylist.backend.story.service.*;
import com.storystylist.backend.ai.*;
import com.storystylist.backend.ai.domain.*;
import com.storystylist.backend.ai.config.AiProperties;
import com.storystylist.backend.ai.validation.VisionResponseCodec;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.photo.service.*;
import com.storystylist.backend.photo.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.security.MessageDigest;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
@WebMvcTest(StoryRecommendationController.class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@Import({StoryRecommendationService.class,RecommendationValidationService.class,VisionResponseCodec.class,
        PhotoPreprocessingService.class,PhotoValidationService.class,StoryControllerTests.Properties.class})
class StoryControllerTests {
    @TestConfiguration @EnableConfigurationProperties(AiProperties.class) static class Properties {}
    @Autowired MockMvc mvc;
    @MockitoBean VisionAiClient client;
    @MockitoBean com.storystylist.backend.storage.StoryStorageService storage;
    @Test void actualMeasuredCaptionFitsButImpossibleCaptionStillFailsWithoutAnotherAiCall() throws Exception {
        for(var provider:AiProvider.values()) for(double width:new double[]{.2,.001}) {
            reset(client); when(client.provider()).thenReturn(provider);
            var tree=(tools.jackson.databind.node.ObjectNode)com.storystylist.backend.ai.AiTestData.JSON.readTree(StoryTests.json());
            var recommendation=tree.path("recommendations").get(0);
            var caption=(tools.jackson.databind.node.ObjectNode)recommendation.path("summary").path("caption");
            caption.put("include",true); caption.put("text","iiiiiiiiii");
            var layers=(tools.jackson.databind.node.ObjectNode)recommendation.path("renderPlan").path("layers");
            var texts=layers.putArray("texts");
            texts.add(com.storystylist.backend.ai.AiTestData.JSON.valueToTree(new com.storystylist.backend.story.dto.TextLayer(
                    "iiiiiiiiii",1,.1,.2,width,.1,com.storystylist.backend.story.dto.FontId.SANS_SERIF,48,"#FFFFFF",
                    com.storystylist.backend.story.dto.TextAlignment.CENTER,0)));
            when(client.generate(anyList(),anyString(),anyString(),anyString(),anyMap())).thenReturn(new StructuredAiResponse(tree.toString(),"model",null));
            var response=mvc.perform(multipart("/api/v1/stories/recommend").file(TestImages.photo("png",20,10)));
            if(width==.2) response.andExpect(status().isOk())
                    .andExpect(jsonPath("$.recommendations[0].renderPlan.layers.texts[0].fontSize").value(48))
                    .andExpect(jsonPath("$.recommendations[0].renderPlan.layers.texts[0].resolvedLayout.lines.length()").value(1))
                    .andExpect(jsonPath("$.recommendations[0].summary.caption.text").value("iiiiiiiiii"));
            else response.andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_INVALID_RESPONSE"));
            verify(client,times(1)).generate(anyList(),anyString(),anyString(),anyString(),anyMap());
            verify(client,never()).analyze(anyList(),any());
        }
    }
    @Test void resolvesMismatchedAiCropWithOneCall() throws Exception {
        when(client.provider()).thenReturn(AiProvider.GEMINI);
        when(client.generate(anyList(),anyString(),anyString(),anyString(),anyMap())).thenReturn(
                new StructuredAiResponse(StoryTests.json().replace("\"height\":0.28125","\"height\":0.7"),"model",null));
        var response=mvc.perform(multipart("/api/v1/stories/recommend").file(TestImages.photo("png",20,10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendations[0].renderPlan.schemaVersion").value("story-render-plan-v3"))
                .andExpect(jsonPath("$.recommendations[0].renderPlan.layers.images[0].fitMode").value("COVER"))
                .andReturn();
        var layer=com.storystylist.backend.ai.AiTestData.JSON.readTree(response.getResponse().getContentAsString())
                .path("recommendations").get(0).path("renderPlan").path("layers").path("images").get(0);
        double ratio=layer.path("crop").path("width").asDouble()*20/(layer.path("crop").path("height").asDouble()*10);
        assertThat(ratio).isCloseTo(1080./(.7*1920),within(1e-10));
        verify(client,times(1)).generate(anyList(),anyString(),anyString(),anyString(),anyMap());
        verify(client,never()).analyze(anyList(),any());
    }
    @Test void logsSafeParsingAndValidationStages(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        when(client.generate(anyList(),anyString(),anyString(),anyString(),anyMap()))
                .thenReturn(new StructuredAiResponse("private-response-content", "model",null))
                .thenReturn(new StructuredAiResponse(StoryTests.json().replace("\"height\":0.28125","\"height\":2.0"),"model",null));
        for(int i=0;i<2;i++) {
            mvc.perform(multipart("/api/v1/stories/recommend").file(TestImages.photo("png",20,10)).param("prompt","private-user-context"))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_INVALID_RESPONSE"));
            assertThat(org.slf4j.MDC.get("storyRequestId")).isNull();
        }
        assertThat(output.getOut()).contains("Story recommendation failed:","stage=JSON_PARSING",
                "stage=RENDER_PLAN_NORMALIZATION", "rule=image.destination.height.finiteOrRange")
                .doesNotContain("private-response-content", "A quiet moment");
        verify(client,times(2)).generate(anyList(),anyString(),anyString(),anyString(),anyMap());
        verify(client,never()).analyze(anyList(),any());
    }
    @ParameterizedTest @ValueSource(ints={1,2,10}) void realUploadsSingleCallAndOriginalHashes(int count) throws Exception {
        for(var provider:AiProvider.values()) {
            reset(client); when(client.provider()).thenReturn(provider);
            when(client.generate(anyList(),anyString(),anyString(),eq("story_recommendation_v3"),anyMap())).thenAnswer(call->{
                List<com.storystylist.backend.photo.dto.ProcessedPhoto> photos=call.getArgument(0);
                assertThat(photos).hasSize(count);
                assertThat(photos.getFirst().metadata().processedMediaType()).isEqualTo("image/jpeg");
                return new StructuredAiResponse(StoryTests.json(),"test-model",new AiUsageMetadata(10L,20L,40L));
            });
            var request=multipart("/api/v1/stories/recommend");
            var photo=TestImages.photo("png",20,10);
            for(int i=0;i<count;i++) request.file(photo);
            if(count>1) request.param("prompt","A quiet afternoon");
            var result=mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECOMMENDED"))
                    .andExpect(jsonPath("$.provider").value(provider.name())).andExpect(jsonPath("$.photoCount").value(count))
                    .andExpect(jsonPath("$.recommendations[0].summary.selectedPhotoIds.length()").value(1))
                    .andExpect(jsonPath("$.recommendations[0].renderPlan.layers.images.length()").value(1))
                    .andExpect(jsonPath("$.assets[0].sha256").value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(photo.getBytes()))))
                    .andExpect(jsonPath("$.assets[0].mediaType").value("image/png"))
                    .andExpect(jsonPath("$.usage.totalTokens").value(40)).andReturn();
            var tree=com.storystylist.backend.ai.AiTestData.JSON.readTree(result.getResponse().getContentAsString());
            assertThat(UUID.fromString(tree.path("recommendations").get(0).path("recommendationId").asString())).isNotNull();
            verify(client,times(1)).generate(anyList(),anyString(),anyString(),anyString(),anyMap());
            verify(client,never()).analyze(anyList(),any());
        }
    }
    @Test void persistenceFailureCannotReportSuccessOrInvokeAiAgain() throws Exception {
        when(client.provider()).thenReturn(AiProvider.GEMINI);
        when(client.generate(anyList(),anyString(),anyString(),anyString(),anyMap())).thenReturn(new StructuredAiResponse(StoryTests.json(),"model",null));
        doThrow(new com.storystylist.backend.storage.StorageException("STORAGE_UNAVAILABLE","Temporary story storage is unavailable",503)).when(storage).save(any(),anyList());
        mvc.perform(multipart("/api/v1/stories/recommend").file(TestImages.photo("png",20,10)))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        verify(client,times(1)).generate(anyList(),anyString(),anyString(),anyString(),anyMap());
    }
    @Test void invalidInputsNeverReachAi() throws Exception {
        mvc.perform(multipart("/api/v1/stories/recommend")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/stories/recommend").file(TestImages.photo("png",20,10)).param("prompt","x".repeat(301)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }
    @Test void propagatesProviderFailuresWithoutRepairOrFallback() throws Exception {
        for(var error:List.of(AiError.AI_PROVIDER_TIMEOUT,AiError.AI_RATE_LIMITED,AiError.AI_PROVIDER_UNAVAILABLE,AiError.AI_CONFIGURATION_ERROR)) {
            reset(client); doThrow(new AiException(error)).when(client).generate(anyList(),anyString(),anyString(),anyString(),anyMap());
            mvc.perform(multipart("/api/v1/stories/recommend").file(TestImages.photo("png",20,10)))
                    .andExpect(status().is(error.status.value())).andExpect(jsonPath("$.code").value(error.name()));
            verify(client,times(1)).generate(anyList(),anyString(),anyString(),anyString(),anyMap());
            verifyNoMoreInteractions(client);
        }
    }
    @Test void invalidAiOutputReturnsControlledError() throws Exception {
        for(String json:List.of("{}", "not-json",StoryTests.json().replace("photo-1","unknown"))) {
            reset(client); when(client.generate(anyList(),anyString(),anyString(),anyString(),anyMap())).thenReturn(new StructuredAiResponse(json,"model",null));
            mvc.perform(multipart("/api/v1/stories/recommend").file(TestImages.photo("png",20,10)))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_INVALID_RESPONSE"));
            verify(client,times(1)).generate(anyList(),anyString(),anyString(),anyString(),anyMap());
        }
    }
}
