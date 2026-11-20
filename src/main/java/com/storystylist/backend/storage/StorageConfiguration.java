package com.storystylist.backend.storage;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
@Configuration @EnableScheduling @EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {
 /** Verify the target before Flyway can baseline a non-empty schema. Never create a database. */
 @Bean public org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy storyMigrations(
   org.springframework.core.env.Environment environment,com.storystylist.backend.photo.config.PhotoValidationProperties limits) {
  return flyway->{
   try(var connection=flyway.getConfiguration().getDataSource().getConnection()) {
    String database=connection.getMetaData().getDatabaseProductName();
    boolean isolatedTest=database.equals("H2") && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("test"));
    if(!isolatedTest) {
     if(!database.equals("MySQL") || !"java16".equals(connection.getCatalog()))
      throw new IllegalStateException("Story storage requires the MySQL database java16");
     try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT @@max_allowed_packet")) {
      if(!result.next() || result.getLong(1)<2*limits.maxFileSizeBytes()+1048576)
       throw new IllegalStateException("MySQL max_allowed_packet is too small for story photos; configure at least 32 MiB and restart the application");
     }
    }
   }catch(java.sql.SQLException e){throw new IllegalStateException("Unable to connect to story storage; check local MySQL configuration");}
   flyway.migrate();
  };
 }
 @Bean public Clock storyStorageClock(){return Clock.systemUTC();}
}
