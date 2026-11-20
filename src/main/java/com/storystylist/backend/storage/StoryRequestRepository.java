package com.storystylist.backend.storage;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
public interface StoryRequestRepository extends JpaRepository<StoredStoryRequest,String> {
 @Query("select r.id from StoredStoryRequest r where r.expiresAt <= :now and not exists (select c.id from StoredRecommendation c where c.storyRequestId=r.id) order by r.expiresAt")
 List<String> emptyExpired(Instant now, Pageable page);
}
