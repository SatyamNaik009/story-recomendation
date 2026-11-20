package com.storystylist.backend.storage;

import com.storystylist.backend.ai.*;
import com.storystylist.backend.ai.domain.*;
import com.storystylist.backend.photo.TestImages;
import com.storystylist.backend.photo.service.*;
import com.storystylist.backend.render.StoryRenderTests;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.StoryTests;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import java.time.*;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.storystylist.backend.ai.AiTestData.JSON;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class StoryStorageTests {
 @Autowired StoryStorageService storage;
 @Autowired StoredStoryRenderer renderer;
 @Autowired StoryRequestRepository requests;
 @Autowired RecommendationRepository recommendations;
 @MockitoSpyBean StoryPhotoRepository photos;
 @Autowired JdbcTemplate jdbc;
 @Autowired MockMvc mvc;
 @MockitoBean VisionAiClient ai;
 @MockitoBean Clock clock;
 static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");
 @BeforeEach void setup(){
  when(clock.instant()).thenReturn(NOW);
  recommendations.deleteAllInBatch();photos.deleteAllInBatch();requests.deleteAllInBatch();
 }
 record Fixture(StoryRecommendationResponse response,List<MultipartFile> files){}
 Fixture fixture(int alternatives) throws Exception {
  List<MultipartFile> files=List.of(StoryRenderTests.image(),TestImages.photo("png",100,50));
  var assets=List.of(PhotoAssetReader.read(files.get(0),"photo-1"),PhotoAssetReader.read(files.get(1),"photo-2"));
  var items=new ArrayList<StoryRecommendation>();
  for(int i=0;i<alternatives;i++) {
   String id="photo-"+(2-i);
   var plan=StoryRenderTests.plan(assets,id,0,true,new ImageAdjustments(0.,1.,1.));
   var summary=new StorySummary("Stored story",List.of(id),"Keep the photo","Good composition","Single image",
    new CaptionSuggestion(true,"Out with the crew"),"Simple and natural");
   items.add(new StoryRecommendation(UUID.randomUUID().toString(),summary,plan));
  }
  return new Fixture(new StoryRecommendationResponse("RECOMMENDED",UUID.randomUUID().toString(),AiProvider.GEMINI,"test-model",2,
   items,assets,10,null,"test"),files);
 }
 @Test void savesOriginalsSharedRequestOrderingHashesExactPlansAndPublicIds() throws Exception {
  var f=fixture(2);storage.save(f.response(),f.files());
  assertThat(requests.count()).isEqualTo(1);assertThat(photos.count()).isEqualTo(2);assertThat(recommendations.count()).isEqualTo(2);
  var stored=photos.findAll().stream().sorted(Comparator.comparingInt(p->p.uploadOrder)).toList();
  for(int i=0;i<2;i++) {
   assertThat(stored.get(i).photoId).isEqualTo("photo-"+(i+1));
   assertThat(stored.get(i).originalImage).isEqualTo(f.files().get(i).getBytes());
   assertThat(stored.get(i).sha256).isEqualTo(f.response().assets().get(i).sha256());
   assertThat(stored.get(i).storyRequestId).isEqualTo(f.response().requestId());
  }
  for(var expected:f.response().recommendations()) {
   var actual=recommendations.findById(expected.recommendationId()).orElseThrow();
   assertThat(JSON.readValue(actual.renderPlanJson,StoryRenderPlan.class)).isEqualTo(expected.renderPlan());
   assertThat(JSON.readValue(actual.summaryJson,StorySummary.class)).isEqualTo(expected.summary());
   assertThat(actual.expiresAt).isEqualTo(NOW.plus(Duration.ofHours(24)));
  }
  verifyNoInteractions(ai);
 }
 @Test void rendersUsingOnlyIdAndLoadsOnlySelectedOriginalWithStableId() throws Exception {
  var f=fixture(1);storage.save(f.response(),f.files());clearInvocations(photos);
  try(var decoder=mockStatic(OriginalPhotoDecoder.class,CALLS_REAL_METHODS)) {
   var png=renderer.render(f.response().recommendations().getFirst().recommendationId());
   var image=ImageIO.read(new ByteArrayInputStream(png));
   try {assertThat(image.getWidth()).isEqualTo(1080);assertThat(image.getHeight()).isEqualTo(1920);}finally{image.flush();}
   decoder.verify(()->OriginalPhotoDecoder.decode(any(),argThat(a->a.photoId().equals("photo-2"))),times(1));
   decoder.verifyNoMoreInteractions();
  }
  verify(photos).boundedImage(eq(f.response().requestId()),eq("photo-2"),anyLong());
  verify(photos,never()).boundedImage(anyString(),eq("photo-1"),anyLong());
  verifyNoInteractions(ai);
 }
 @Test void jsonRenderEndpointNeedsNoPhotosOrPlan() throws Exception {
  var f=fixture(1);storage.save(f.response(),f.files());
  var result=mvc.perform(post("/api/v1/stories/render").contentType("application/json")
   .content(JSON.writeValueAsString(Map.of("recommendationId",f.response().recommendations().getFirst().recommendationId()))))
   .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
   .andExpect(header().string("Content-Disposition","inline; filename=\"story.png\""))
   .andExpect(header().string("Cache-Control","no-store")).andReturn();
  var image=ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
  try {assertThat(image.getWidth()).isEqualTo(1080);assertThat(image.getHeight()).isEqualTo(1920);}finally{image.flush();}
  verifyNoInteractions(ai);
 }
 @Test void recommendationHttpPersistsSameReturnedIdAndRendersWithoutAnotherAiCall() throws Exception {
  when(ai.provider()).thenReturn(AiProvider.GEMINI);
  when(ai.generate(anyList(),anyString(),anyString(),anyString(),anyMap())).thenAnswer(invocation->{
   assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
   return new StructuredAiResponse(StoryTests.json(),"model",null);
  });
  var result=mvc.perform(multipart("/api/v1/stories/recommend").file(TestImages.photo("png",20,10)))
   .andExpect(status().isOk()).andReturn();
  var response=JSON.readValue(result.getResponse().getContentAsString(),StoryRecommendationResponse.class);
  String id=response.recommendations().getFirst().recommendationId();
  assertThat(recommendations.existsById(id)).isTrue();assertThat(photos.count()).isEqualTo(1);
  clearInvocations(ai);
  mvc.perform(post("/api/v1/stories/render").contentType("application/json").content(JSON.writeValueAsString(Map.of("recommendationId",id))))
   .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
  verifyNoInteractions(ai);
 }
 @Test void failedPersistenceRollsBackRequestPhotosAndRecommendations() throws Exception {
  var f=fixture(2);var items=f.response().recommendations();var first=items.getFirst();var second=items.get(1);
  var duplicate=new StoryRecommendation(first.recommendationId(),second.summary(),second.renderPlan());
  var r=f.response();var invalid=new StoryRecommendationResponse(r.status(),r.requestId(),r.provider(),r.model(),r.photoCount(),List.of(first,duplicate),r.assets(),r.latencyMillis(),r.usage(),r.promptVersion());
  assertThatThrownBy(()->storage.save(invalid,f.files())).isInstanceOf(StorageException.class);
  assertThat(requests.count()).isZero();assertThat(photos.count()).isZero();assertThat(recommendations.count()).isZero();
 }
 @ParameterizedTest @ValueSource(strings={"", "not-uuid", "1-1-1-1-1", "../../etc/passwd"})
 void rejectsInvalidIdentifiers(String id) throws Exception {
  mvc.perform(post("/api/v1/stories/render").contentType("application/json").content(JSON.writeValueAsString(Map.of("recommendationId",id))))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RECOMMENDATION_ID_INVALID"));
  verifyNoInteractions(ai);
 }
 @Test void missingIdMalformedJsonAndNotFoundReturnSafeErrors() throws Exception {
  mvc.perform(post("/api/v1/stories/render").contentType("application/json").content("{}"))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RECOMMENDATION_ID_INVALID"));
  mvc.perform(post("/api/v1/stories/render").contentType("application/json").content("{"))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
  mvc.perform(post("/api/v1/stories/render").contentType("application/json").content(JSON.writeValueAsString(Map.of("recommendationId",UUID.randomUUID().toString()))))
   .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECOMMENDATION_NOT_FOUND"));
 }
 @Test void expiredAtBoundaryRejectedBeforeImageReadEvenWithoutCleanup() throws Exception {
  var f=fixture(1);storage.save(f.response(),f.files());clearInvocations(photos);
  when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(24)));
  mvc.perform(post("/api/v1/stories/render").contentType("application/json").content(JSON.writeValueAsString(Map.of("recommendationId",f.response().recommendations().getFirst().recommendationId()))))
   .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("RECOMMENDATION_EXPIRED"));
  verify(photos,never()).boundedImage(anyString(),anyString(),anyLong());assertThat(recommendations.count()).isEqualTo(1);
 }
 @Test void cleanupDeletesExpiredChildrenAndParentsButPreservesActiveSharedPhotos() throws Exception {
  var f=fixture(2);storage.save(f.response(),f.files());
  jdbc.update("update story_recommendations set expires_at=? where id=?",java.time.LocalDateTime.ofInstant(NOW.plus(Duration.ofHours(48)),java.time.ZoneOffset.UTC),f.response().recommendations().getFirst().recommendationId());
  when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(25)));
  var first=storage.cleanup();assertThat(first.recommendations()).isEqualTo(1);assertThat(first.photos()).isZero();
  assertThat(requests.count()).isEqualTo(1);assertThat(photos.count()).isEqualTo(2);
  when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(49)));
  var last=storage.cleanup();assertThat(last.recommendations()).isEqualTo(1);assertThat(last.photos()).isEqualTo(2);assertThat(last.requests()).isEqualTo(1);
  assertThat(requests.count()).isZero();assertThat(photos.count()).isZero();assertThat(recommendations.count()).isZero();
 }
 @Test void cleanupLeavesActiveRequestsUntouched() throws Exception {
  var f=fixture(2);storage.save(f.response(),f.files());assertThat(storage.cleanup()).isEqualTo(new StoryStorageService.CleanupCounts(0,0,0));
  assertThat(recommendations.count()).isEqualTo(2);assertThat(photos.count()).isEqualTo(2);
 }
 @ParameterizedTest @ValueSource(strings={"hash","missing","bytes","plan","metadata","oversized"})
 void corruptOrMissingStoredDataCannotRender(String kind) throws Exception {
  var f=fixture(1);storage.save(f.response(),f.files());String request=f.response().requestId();String id=f.response().recommendations().getFirst().recommendationId();
  switch(kind) {
   case "hash" -> jdbc.update("update story_photos set sha256=? where story_request_id=? and photo_id='photo-2'","0".repeat(64),request);
   case "missing" -> jdbc.update("delete from story_photos where story_request_id=? and photo_id='photo-2'",request);
   case "bytes" -> jdbc.update("update story_photos set original_image=? where story_request_id=? and photo_id='photo-2'",new byte[]{1,2,3},request);
   case "plan" -> jdbc.update("update story_recommendations set render_plan_json='{}' where id=?",id);
   case "metadata" -> jdbc.update("update story_photos set oriented_width=7 where story_request_id=? and photo_id='photo-2'",request);
   case "oversized" -> jdbc.update("update story_photos set original_image=? where story_request_id=? and photo_id='photo-2'",new byte[10485761],request);
  }
  mvc.perform(post("/api/v1/stories/render").contentType("application/json").content(JSON.writeValueAsString(Map.of("recommendationId",id))))
   .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("STORED_STORY_INVALID"));
  verifyNoInteractions(ai);
 }
 @Test void databaseUnavailableIsSanitized() throws Exception {
  var f=fixture(1);storage.save(f.response(),f.files());
  doThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL password details"))
   .when(photos).selectedMetadata(anyString(),anyCollection());
  var result=mvc.perform(post("/api/v1/stories/render").contentType("application/json")
   .content(JSON.writeValueAsString(Map.of("recommendationId",f.response().recommendations().getFirst().recommendationId()))))
   .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE")).andReturn();
  assertThat(result.getResponse().getContentAsString()).doesNotContain("private","password","SQL");
 }
 @Test void cleanupSchedulerContainsFailuresAndCanRunAgain(){
  var mock=mock(StoryStorageService.class);when(mock.cleanup()).thenThrow(new RuntimeException("private")).thenReturn(new StoryStorageService.CleanupCounts(1,2,1));
  var scheduler=new StoryCleanupScheduler(mock);assertThatCode(scheduler::cleanup).doesNotThrowAnyException();scheduler.cleanup();verify(mock,times(2)).cleanup();
 }
}

