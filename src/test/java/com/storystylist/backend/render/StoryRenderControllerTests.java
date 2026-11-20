package com.storystylist.backend.render;
import com.storystylist.backend.render.controller.*;
import com.storystylist.backend.render.service.*;
import com.storystylist.backend.render.validation.*;
import com.storystylist.backend.story.service.*;
import com.storystylist.backend.photo.service.*;
import com.storystylist.backend.ai.VisionAiClient;
import com.storystylist.backend.ai.validation.VisionResponseCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.List;
import static com.storystylist.backend.ai.AiTestData.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(StoryRenderController.class)
@Import({SinglePhotoLayoutRefiner.class,StoryRenderer.class,OriginalPhotoAssetResolver.class,PhotoValidationService.class,VisionResponseCodec.class,
        RenderSpecificationCompiler.class,RenderSpecificationValidator.class,RecommendationValidationService.class,
        Java2DStoryRenderer.class,StoryImageEncodingService.class})
class StoryRenderControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean VisionAiClient ai;
    @MockitoBean com.storystylist.backend.storage.StoredStoryRenderer stored;
    @Test void rendersActualMultipartPngWithoutAi() throws Exception {
        var file=StoryRenderTests.image();var assets=StoryRenderTests.assets(List.of(file));var plan=StoryRenderTests.plan(assets,"photo-1",0,true,StoryRenderTests.NEUTRAL);
        var result=mvc.perform(multipart("/api/v1/stories/render").file(file).param("renderPlan",JSON.writeValueAsString(plan)).param("assets",JSON.writeValueAsString(assets)))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Disposition","inline; filename=\"story.png\""))
                .andExpect(header().string("Cache-Control","no-store")).andReturn();
        var image=ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
        try {assertThat(image.getWidth()).isEqualTo(1080);assertThat(image.getHeight()).isEqualTo(1920);}finally{image.flush();}
        verifyNoInteractions(ai);
    }
    @Test void rejectsMissingInvalidAndUnsupportedRequestsSafely() throws Exception {
        var file=StoryRenderTests.image();var assets=StoryRenderTests.assets(List.of(file));var plan=StoryRenderTests.plan(assets,"photo-1",0,false,StoryRenderTests.NEUTRAL);
        String json=JSON.writeValueAsString(plan),metadata=JSON.writeValueAsString(assets);
        mvc.perform(multipart("/api/v1/stories/render")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RENDER_PLAN_MISSING"));
        mvc.perform(multipart("/api/v1/stories/render").param("renderPlan","bad").param("assets",metadata)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RENDER_JSON_INVALID"));
        mvc.perform(multipart("/api/v1/stories/render").param("renderPlan",json).param("assets",metadata)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/stories/render").file(file).param("renderPlan",json.replace("SINGLE","SPLIT_TWO")).param("assets",metadata))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("RENDER_LAYOUT_UNSUPPORTED"));
        mvc.perform(multipart("/api/v1/stories/render").file(file).param("renderPlan",json).param("assets",metadata.replace(assets.getFirst().sha256(),"0".repeat(64))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RENDER_ASSET_MISMATCH"));
        mvc.perform(multipart("/api/v1/stories/render").file(file).param("renderPlan",json.replace("\"rotation\":0","\"rotation\":45")).param("assets",metadata))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RENDER_PLAN_INVALID"));
        verifyNoInteractions(ai);
    }
}
