package com.storystylist.backend.storage;
import jakarta.persistence.*;
@Entity @Table(name="story_requests")
public class StoredStoryRequest {
 @Id @Column(length=36) String id;
 @Column(nullable=false) java.time.Instant createdAt;
 @Column(nullable=false) java.time.Instant expiresAt;
 @Column(nullable=false,length=16) String provider;
 @Column(nullable=false,length=255) String model;
 @Column(nullable=false,length=32) String status;
 protected StoredStoryRequest(){}
}
