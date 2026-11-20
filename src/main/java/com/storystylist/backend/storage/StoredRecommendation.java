package com.storystylist.backend.storage;
import jakarta.persistence.*;
@Entity @Table(name="story_recommendations")
public class StoredRecommendation {
 @Id @Column(length=36) String id;
 @Column(nullable=false,length=36) String storyRequestId;
 @Column(nullable=false,length=512) String title;
 @Column(nullable=false,columnDefinition="LONGTEXT") String summaryJson;
 @Column(nullable=false,columnDefinition="LONGTEXT") String renderPlanJson;
 @Column(nullable=false) java.time.Instant createdAt;
 @Column(nullable=false) java.time.Instant expiresAt;
 protected StoredRecommendation(){}
}
