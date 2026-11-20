package com.storystylist.backend.storage;
import java.util.Optional;
/** Bounded BLOB access, joined to the caller's JPA transaction. */
public interface StoryPhotoBytesRepository {
 Optional<byte[]> boundedImage(String requestId,String photoId,long maxBytes);
}
