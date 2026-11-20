package com.storystylist.backend.storage;
import com.storystylist.backend.photo.config.PhotoValidationProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StorageConfigurationTests {
 static PhotoValidationProperties limits(){return new PhotoValidationProperties(10,10485760,300,25000000,10000,10000);}
 record Setup(Flyway flyway,Connection connection,Statement statement,ResultSet result){}
 Setup mockDatabase(String database,String catalog,long packet) throws Exception {
  var flyway=mock(Flyway.class);var configuration=mock(Configuration.class);var source=mock(DataSource.class);
  var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);var statement=mock(Statement.class);var result=mock(ResultSet.class);
  when(flyway.getConfiguration()).thenReturn(configuration);when(configuration.getDataSource()).thenReturn(source);when(source.getConnection()).thenReturn(connection);
  when(connection.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn(database);when(connection.getCatalog()).thenReturn(catalog);
  when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery("SELECT @@max_allowed_packet")).thenReturn(result);when(result.next()).thenReturn(true);when(result.getLong(1)).thenReturn(packet);
  return new Setup(flyway,connection,statement,result);
 }
 @Test void refusesWrongMySqlDatabaseBeforeMigration() throws Exception {
  var s=mockDatabase("MySQL","unrelated",67108864);
  assertThatThrownBy(()->new StorageConfiguration().storyMigrations(new MockEnvironment(),limits()).migrate(s.flyway()))
   .isInstanceOf(IllegalStateException.class).hasMessageContaining("java16");verify(s.flyway(),never()).migrate();
 }
 @Test void rejectsInsufficientPacketCapacityWithoutChangingServer() throws Exception {
  var s=mockDatabase("MySQL","java16",4194304);
  assertThatThrownBy(()->new StorageConfiguration().storyMigrations(new MockEnvironment(),limits()).migrate(s.flyway())).hasMessageContaining("32 MiB");
  verify(s.flyway(),never()).migrate();verify(s.statement()).executeQuery("SELECT @@max_allowed_packet");verify(s.statement()).close();verifyNoMoreInteractions(s.statement());
 }
 @Test void acceptsJava16AndSufficientPacketCapacity() throws Exception {
  var s=mockDatabase("MySQL","java16",33554432);new StorageConfiguration().storyMigrations(new MockEnvironment(),limits()).migrate(s.flyway());verify(s.flyway()).migrate();
 }
 @Test void h2AllowedOnlyInExplicitTestProfile() throws Exception {
  var s=mockDatabase("H2","test",0);
  assertThatThrownBy(()->new StorageConfiguration().storyMigrations(new MockEnvironment(),limits()).migrate(s.flyway())).isInstanceOf(IllegalStateException.class);
  var environment=new MockEnvironment();environment.setActiveProfiles("test");
  new StorageConfiguration().storyMigrations(environment,limits()).migrate(s.flyway());verify(s.flyway()).migrate();
 }
 @Test void migrationPreservesUnrelatedTablesAndCanRunAgain() throws Exception {
  String url="jdbc:h2:mem:story_migration_contract;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
  try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
   statement.execute("create table unrelated_business(id integer primary key, value_text varchar(30))");
   statement.execute("insert into unrelated_business values (7,'keep this row')");
   var flyway=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/story-migration")
    .table("story_schema_history").baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
   assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);assertThat(flyway.migrate().migrationsExecuted).isZero();
   try(var rows=statement.executeQuery("select value_text from unrelated_business where id=7")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("keep this row");}
   try(var rows=statement.executeQuery("select count(*) from story_requests")){rows.next();assertThat(rows.getInt(1)).isZero();}
  }
 }
 @Test void mapsDatabaseFailuresWithoutIncludingPrivateCause(){
  var e=StorageException.database(new org.springframework.transaction.CannotCreateTransactionException("private credentials"));
  assertThat(e.status()).isEqualTo(503);assertThat(e.getMessage()).doesNotContain("private","credentials");assertThat(e.getCause()).isNull();
  assertThat(StorageException.database(new IllegalStateException("private SQL")).status()).isEqualTo(500);
 }
}
