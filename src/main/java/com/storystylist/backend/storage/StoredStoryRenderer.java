package com.storystylist.backend.storage;
import com.storystylist.backend.render.service.*;
import org.springframework.stereotype.Service;
@Service
public class StoredStoryRenderer {
 private final StoryStorageService storage;private final StoryRenderer renderer;
 public StoredStoryRenderer(StoryStorageService storage,StoryRenderer renderer){this.storage=storage;this.renderer=renderer;}
 public byte[] render(String id){
  var story=storage.load(id); // Read transaction ends before pixel decoding/rendering.
  storage.checkExpiration(story.expiresAt());
  try {return renderer.renderVerified(story.id(),story.plan(),story.assets(),story.originals());}
  catch(RenderRequestException e){
   if(e.status()==422 || e.status()>=500)throw e;
   throw StorageException.corrupt();
  }
 }
}
