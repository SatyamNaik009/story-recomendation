package com.storystylist.backend.storage;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Optional;
public class StoryPhotoBytesRepositoryImpl implements StoryPhotoBytesRepository {
 private final JdbcTemplate jdbc;
 public StoryPhotoBytesRepositoryImpl(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Override public Optional<byte[]> boundedImage(String requestId,String photoId,long maxBytes) {
  // SQL filters the actual BLOB size before JDBC materializes it. IDs are parameters, never SQL fragments.
  var rows=jdbc.query("select original_image from story_photos where story_request_id=? and photo_id=? and octet_length(original_image) between 1 and ?",
   (rs,row)->rs.getBytes(1),requestId,photoId,maxBytes);
  if(rows.size()>1)throw StorageException.corrupt();
  return rows.stream().findFirst();
 }
}
