package com.storystylist.backend.storage;
import jakarta.persistence.*;
@Entity @Table(name="story_photos")
public class StoredStoryPhoto {
 @Id @Column(length=36) String id;
 @Column(nullable=false,length=36) String storyRequestId;
 @Column(nullable=false,length=32) String photoId;
 @Column(nullable=false) int uploadOrder;
 @Lob @Column(nullable=false,columnDefinition="LONGBLOB") byte[] originalImage;
 @Column(nullable=false,length=64) String sha256;
 @Column(nullable=false,length=32) String mediaType;
 @Column(nullable=false) int originalWidth;
 @Column(nullable=false) int originalHeight;
 @Column(nullable=false) int orientedWidth;
 @Column(nullable=false) int orientedHeight;
 @Column(nullable=false) java.time.Instant createdAt;
 protected StoredStoryPhoto(){}
}
