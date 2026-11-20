package com.storystylist.backend.storage;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
@Component @ConditionalOnProperty(name="story.storage.cleanup-enabled",havingValue="true",matchIfMissing=true)
public class StoryCleanupScheduler {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(StoryCleanupScheduler.class);
 private final StoryStorageService storage;
 public StoryCleanupScheduler(StoryStorageService storage){this.storage=storage;}
 @Scheduled(fixedDelayString="${story.storage.cleanup-interval-ms:900000}",initialDelayString="${story.storage.cleanup-interval-ms:900000}")
 public void cleanup(){
  long start=System.nanoTime();
  try {var counts=storage.cleanup();log.info("Story cleanup completed: recommendations={}, photos={}, requests={}, durationMs={}",counts.recommendations(),counts.photos(),counts.requests(),(System.nanoTime()-start)/1_000_000);}
  catch(RuntimeException e){log.warn("Story cleanup failed; will retry at the next scheduled run: durationMs={}",(System.nanoTime()-start)/1_000_000);}
 }
}
