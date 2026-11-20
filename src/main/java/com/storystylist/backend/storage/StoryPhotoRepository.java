package com.storystylist.backend.storage;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface StoryPhotoRepository extends JpaRepository<StoredStoryPhoto,String>, StoryPhotoBytesRepository {
 interface Metadata {
  String getPhotoId(); String getSha256(); String getMediaType(); int getUploadOrder();
  int getOriginalWidth(); int getOriginalHeight(); int getOrientedWidth(); int getOrientedHeight();
 }
 @Query("select p.photoId as photoId,p.sha256 as sha256,p.mediaType as mediaType,p.uploadOrder as uploadOrder,p.originalWidth as originalWidth,p.originalHeight as originalHeight,p.orientedWidth as orientedWidth,p.orientedHeight as orientedHeight from StoredStoryPhoto p where p.storyRequestId=:requestId and p.photoId in :ids order by p.uploadOrder")
 List<Metadata> selectedMetadata(String requestId,Collection<String> ids);
 @Modifying @Query("delete from StoredStoryPhoto p where p.storyRequestId in :ids")
 int deleteRequests(Collection<String> ids);
}
