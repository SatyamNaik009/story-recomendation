package com.storystylist.backend.storage;

import com.storystylist.backend.ai.validation.VisionResponseCodec;
import com.storystylist.backend.photo.config.PhotoValidationProperties;
import com.storystylist.backend.photo.service.*;
import com.storystylist.backend.render.service.*;
import com.storystylist.backend.story.dto.*;
import com.storystylist.backend.story.service.RecommendationValidationService;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.security.*;

@Service
public class StoryStorageService {
 private final StoryRequestRepository requests;
 private final StoryPhotoRepository photos;
 private final RecommendationRepository recommendations;
 private final EntityManager entities;
 private final TransactionTemplate writes,reads;
 private final StorageProperties settings;
 private final PhotoValidationProperties limits;
 private final PhotoValidationService validation;
 private final RecommendationValidationService plans;
 private final VisionResponseCodec codec;
 private final Clock clock;
 private final JsonMapper json=JsonMapper.builder().build();
 public StoryStorageService(StoryRequestRepository requests,StoryPhotoRepository photos,RecommendationRepository recommendations,
   EntityManager entities,PlatformTransactionManager manager,StorageProperties settings,PhotoValidationProperties limits,
   PhotoValidationService validation,RecommendationValidationService plans,VisionResponseCodec codec,Clock clock) {
  this.requests=requests;this.photos=photos;this.recommendations=recommendations;this.entities=entities;
  this.settings=settings;this.limits=limits;this.validation=validation;this.plans=plans;this.codec=codec;this.clock=clock;
  writes=new TransactionTemplate(manager);writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
 }
 /** Called only after AI processing and final validation; commit completes before success is returned. */
 public void save(StoryRecommendationResponse response,List<MultipartFile> uploads) {
  validation.inspect(uploads,null);
  plans.validate(new StoryAiResult(response.recommendations().stream().map(r->new StoryDraft(r.summary(),r.renderPlan())).toList()),response.assets());
  if(response.assets().size()!=uploads.size())throw StorageException.corrupt();
  try {
   writes.executeWithoutResult(tx->{
    Instant now=clock.instant(),expiry=now.plus(Duration.ofHours(settings.retentionHours()));
    var request=new StoredStoryRequest();request.id=response.requestId();request.createdAt=now;request.expiresAt=expiry;
    request.provider=response.provider().name();request.model=response.model();request.status="RECOMMENDED";entities.persist(request);
    for(int i=0;i<uploads.size();i++) {
     var asset=response.assets().get(i);
     if(!("photo-"+(i+1)).equals(asset.photoId()))throw StorageException.corrupt();
     byte[] bytes;
     try(var input=uploads.get(i).getInputStream()){bytes=input.readNBytes(Math.toIntExact(limits.maxFileSizeBytes()+1));}
     catch(java.io.IOException e){throw StorageException.corrupt();}
     if(bytes.length==0 || bytes.length>limits.maxFileSizeBytes() || !hash(bytes).equalsIgnoreCase(asset.sha256()))throw StorageException.corrupt();
     var p=new StoredStoryPhoto();p.id=UUID.randomUUID().toString();p.storyRequestId=request.id;p.photoId=asset.photoId();p.uploadOrder=i;
     p.originalImage=bytes;p.sha256=asset.sha256();p.mediaType=asset.mediaType();p.originalWidth=asset.originalWidth();p.originalHeight=asset.originalHeight();
     p.orientedWidth=asset.orientedWidth();p.orientedHeight=asset.orientedHeight();p.createdAt=now;
     entities.persist(p);entities.flush();entities.detach(p); // Do not retain every original in the persistence context.
    }
    for(var item:response.recommendations()) {
     var r=new StoredRecommendation();r.id=item.recommendationId();r.storyRequestId=request.id;r.title=item.summary().title();
     r.summaryJson=json.writeValueAsString(item.summary());r.renderPlanJson=json.writeValueAsString(item.renderPlan());r.createdAt=now;r.expiresAt=expiry;
     entities.persist(r);
    }
   });
  }catch(StorageException e){throw e;}catch(RuntimeException e){throw StorageException.database(e);}
 }
 public record LoadedStory(String id,StoryRenderPlan plan,List<PhotoAssetMetadata> assets,Map<String,MultipartFile> originals,Instant expiresAt){}
 public LoadedStory load(String id) {
  if(id==null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
   throw new StorageException("RECOMMENDATION_ID_INVALID","Supply a valid recommendation UUID",400);
  String canonical=UUID.fromString(id).toString();
  try {return reads.execute(tx->{
   var r=recommendations.findById(canonical).orElseThrow(()->new StorageException("RECOMMENDATION_NOT_FOUND","Recommendation was not found",404));
   checkExpiration(r.expiresAt);
   StoryRenderPlan plan;StorySummary summary;
   try {plan=codec.parse(r.renderPlanJson,StoryRenderPlan.class);summary=codec.parse(r.summaryJson,StorySummary.class);}
   catch(RuntimeException e){throw StorageException.corrupt();}
   if(plan==null || plan.layers()==null || plan.layers().images()==null || plan.layers().images().stream().anyMatch(Objects::isNull) || summary==null || summary.selectedPhotoIds()==null)throw StorageException.corrupt();
   var ids=plan.layers().images().stream().map(ImageLayer::photoId).toList();
   if(ids.isEmpty() || ids.size()>4 || new HashSet<>(ids).size()!=ids.size() || ids.stream().anyMatch(Objects::isNull)
      || summary.selectedPhotoIds().size()!=ids.size() || !new HashSet<>(ids).equals(new HashSet<>(summary.selectedPhotoIds())))throw StorageException.corrupt();
   var metadata=photos.selectedMetadata(r.storyRequestId,ids);
   if(metadata.size()!=ids.size())throw StorageException.corrupt();
   var assets=new ArrayList<PhotoAssetMetadata>();var originals=new LinkedHashMap<String,MultipartFile>();
   for(var p:metadata) {
    if(p.getUploadOrder()<0 || p.getUploadOrder()>=limits.maxPhotos() || !ids.contains(p.getPhotoId()) || !("photo-"+(p.getUploadOrder()+1)).equals(p.getPhotoId()))throw StorageException.corrupt();
    var bytes=photos.boundedImage(r.storyRequestId,p.getPhotoId(),limits.maxFileSizeBytes()).orElseThrow(StorageException::corrupt);
    if(bytes==null || bytes.length==0 || bytes.length>limits.maxFileSizeBytes() || !hash(bytes).equalsIgnoreCase(p.getSha256()))throw StorageException.corrupt();
    var file=new StoredPhotoFile(bytes,p.getMediaType());
    var expected=new PhotoAssetMetadata(p.getPhotoId(),p.getSha256(),p.getOriginalWidth(),p.getOriginalHeight(),p.getMediaType(),p.getOrientedWidth(),p.getOrientedHeight());
    try {
     validation.inspect(List.of(file),null); // Enforces existing signature, dimensions, pixel and byte limits.
     if(!expected.equals(PhotoAssetReader.read(file,p.getPhotoId())))throw StorageException.corrupt();
    }catch(RuntimeException e){throw StorageException.corrupt();}
    assets.add(expected);if(originals.put(p.getPhotoId(),file)!=null)throw StorageException.corrupt();
   }
   try {plans.validate(new StoryAiResult(List.of(new StoryDraft(summary,plan))),assets);}catch(RuntimeException e){throw StorageException.corrupt();}
   return new LoadedStory(r.id,plan,List.copyOf(assets),Collections.unmodifiableMap(originals),r.expiresAt);
  });}catch(StorageException e){throw e;}catch(RuntimeException e){throw StorageException.database(e);}
 }
 public void checkExpiration(Instant expiry){
  if(expiry==null)throw StorageException.corrupt();
  if(!expiry.isAfter(clock.instant()))throw new StorageException("RECOMMENDATION_EXPIRED","Recommendation has expired",410);
 }
 public record CleanupCounts(int recommendations,int photos,int requests){}
 public CleanupCounts cleanup() {
  try {return writes.execute(tx->{
   Instant now=clock.instant();
   var expired=recommendations.expiredIds(now,PageRequest.of(0,500));
   if(!expired.isEmpty())recommendations.deleteAllByIdInBatch(expired);
   var empty=requests.emptyExpired(now,PageRequest.of(0,200));
   int deletedPhotos=empty.isEmpty()?0:photos.deleteRequests(empty);
   if(!empty.isEmpty())requests.deleteAllByIdInBatch(empty);
   return new CleanupCounts(expired.size(),deletedPhotos,empty.size());
  });}catch(RuntimeException e){throw StorageException.database(e);}
 }
 private static String hash(byte[] bytes){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable");}
 }
}
