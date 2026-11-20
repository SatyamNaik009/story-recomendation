package com.storystylist.backend.storage;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
public interface RecommendationRepository extends JpaRepository<StoredRecommendation,String> {
 @Query("select r.id from StoredRecommendation r where r.expiresAt <= :now order by r.expiresAt")
 List<String> expiredIds(Instant now, Pageable page);
}
