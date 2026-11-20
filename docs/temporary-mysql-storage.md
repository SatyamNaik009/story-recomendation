# Temporary MySQL story storage

This update adds persistence after Milestone 6. Original images live temporarily in MySQL for this local MVP; an object store can replace the storage adapter later. There is no S3, new AI provider, authentication, or additional rendering implementation.

## Local setup

1. Run MySQL (8.0 or newer) on localhost:3306. Create **java16** manually if it does not exist. The application never creates or renames a database.
2. Edit the existing `src/main/resources/application.properties` locally:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/java16
spring.datasource.username=YOUR_MYSQL_USERNAME
spring.datasource.password=YOUR_MYSQL_PASSWORD
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
story.storage.retention-hours=24
story.storage.cleanup-interval-ms=900000
story.storage.cleanup-enabled=true
```

Use a local account with DDL permissions for the new story tables and CRUD permissions for them. Existing configured values are preserved. API provider/model/key properties remain unchanged. No environment variable is required. Never commit actual passwords or API keys; application.properties is excluded by this workspace's .gitignore. If a future repository tracks that file, .gitignore alone will not untrack it.

3. Check the server's packet capacity in your MySQL client:

```sql
SELECT DATABASE();
SHOW VARIABLES LIKE 'max_allowed_packet';
```

For the existing 10 MiB photo limit, configure **at least 32 MiB** on your local server if necessary. The startup check requires more than twice the configured per-photo byte limit plus 1 MiB, allowing for connector escaping/statement overhead. The application never changes server limits. Reconnect/restart the application after changing the server setting. See [MySQL's packet-size documentation](https://dev.mysql.com/doc/refman/8.4/en/packet-too-large.html). Individual photos are inserted separately; JDBC batching is disabled.

4. Use JDK 25 and run `.\mvnw.cmd spring-boot:run`. Startup requires a reachable database and valid local database credentials. Five pooled connections, a five-second pool acquisition timeout, and disabled Open Session in View keep database access bounded. The startup migration guard refuses a MySQL database other than java16 and checks packet capacity before migrations.

## Schema and migrations

Spring Boot manages the versions of Spring Data JPA, MySQL Connector/J, and Flyway (including its MySQL module). Java, Spring Boot, provider SDK versions, and the existing renderer are unchanged.

`src/main/resources/db/story-migration/V1__temporary_story_storage.sql` creates only:

| Table | Data |
| --- | --- |
| story_requests | Request UUID, provider/model, status, creation and expiration timestamps |
| story_photos | One original LONGBLOB per upload, stable photo ID, upload order, SHA-256, media type, source/oriented dimensions |
| story_recommendations | Public recommendation UUID, request reference, title, complete summary and finalized render-plan JSON, timestamps |
| story_schema_history | Dedicated Flyway migration history |

The three domain tables use InnoDB and explicitly select utf8mb4, preserving Unicode summaries and captions even if java16 has another default charset. Foreign keys link child records to their request; indexes cover expiration and request lookups. Per-request photo IDs and upload positions are unique. All alternatives share the request's photo records. JSON is serialized as portable LONGTEXT, not Java object serialization; strict parsing and the existing validators run before rendering.

Hibernate uses `ddl-auto=validate`, never create-drop or automatic updates. Flyway clean is disabled. A dedicated history table and baseline version **0** allow migration V1 to run when java16 already contains unrelated tables. [Flyway applies migrations above the baseline version](https://documentation.red-gate.com/fd/flyway-baseline-on-migrate-setting-277578974.html). Only the application's migration directory is scanned. A conflicting existing story table causes startup failure rather than silently replacing it. No unrelated table is modified or deleted.

## Recommendation transaction

`StoryRecommendationService` retains the existing upload validation, preprocessing, single provider request, normalization, and validation. It then calls `StoryStorageService.save` with the finalized response and the original multipart files.

A separate transaction persists the request, original bytes/metadata, and all alternatives. The public UUIDs are used directly as database IDs. Photos are flushed individually and detached to avoid retaining all large arrays in the persistence context. Commit must succeed before the API returns RECOMMENDED. Any failure rolls back all writes and returns a controlled storage error. No database transaction spans the external AI call. A failed save does not trigger another AI call; the preceding provider call may already have incurred usage charges.

JPA entities and repositories are `StoredStoryRequest`/`StoryRequestRepository`, `StoredStoryPhoto`/`StoryPhotoRepository`, and `StoredRecommendation`/`RecommendationRepository` under `storage`.

## Rendering by ID

The JSON path is now preferred:

```http
POST /api/v1/stories/render
Content-Type: application/json

{"recommendationId":"<UUID returned by recommend>"}
```

`StoredStoryRenderer` loads the recommendation in a short repeatable-read transaction, checks expiry, parses its plan/summary, and looks up only image IDs selected by that plan. It preserves identifiers and original upload positions: selecting photo-2 does not rename it photo-1. A SQL byte-length predicate rejects oversized BLOBs **before** returning them to Java. Each selected image passes SHA-256, signature, dimension and metadata checks. Unselected image bytes are not fetched or decoded.

After the read transaction ends, expiry is checked again and the existing `StoryRenderer`, `RenderSpecificationCompiler`, and Java2D engine generate the PNG. Rendering does not call Gemini or OpenAI. Originals remain in memory only for the request; no permanent local files are written. A render admitted before expiry may finish after expiry. The repeatable-read snapshot prevents cleanup from causing partial input reads.

Success: 200, `image/png`, `inline; filename="story.png"`, `Cache-Control: no-store`, exactly 1080 x 1920. **Only SINGLE layouts are supported**, as in Milestone 6. Multi-photo alternatives can be saved but still return 422 when rendered; this update does not implement Milestone 7.

The original multipart route at the same URL remains available, selected by `consumes=multipart/form-data`. It still requires complete ordered originals, assets and the render plan. Existing recommendation response fields are unchanged.

## Expiry and cleanup

Retention defaults to 24 hours (configurable from 1 to 168 hours). Each save stores explicit UTC creation/expiry timestamps; changing the setting affects new requests only. Rendering checks expiry even before cleanup runs: expired records return 410. Once deleted, their IDs return 404.

`StoryCleanupScheduler` runs every 15 minutes by default, with the same initial delay. Each transaction deletes up to 500 expired recommendations, then up to 200 expired requests with no remaining recommendation, together with their photos. An active alternative always protects shared photos. Bounded batches prevent one run from taking an unlimited transaction; a large backlog requires subsequent runs. Failures are logged safely and retried on the next scheduled run; logs contain counts and duration, not image data or captions.

Expiry denies access immediately, but physical deletion occurs on the next successful batch while the application is running. If the application is stopped, cleanup waits until it runs again. Database backups/binlogs may retain deleted data according to your MySQL administration policy; deletion is not guaranteed secure erasure.

## Errors

| HTTP | Code / situation |
| --- | --- |
| 400 | RECOMMENDATION_ID_INVALID, INVALID_JSON |
| 404 | RECOMMENDATION_NOT_FOUND |
| 410 | RECOMMENDATION_EXPIRED |
| 422 | RENDER_LAYOUT_UNSUPPORTED |
| 500 | STORED_STORY_INVALID (missing/corrupt photo, hash/metadata mismatch, invalid stored plan), STORAGE_FAILED, existing rendering/encoding failures |
| 503 | STORAGE_UNAVAILABLE (database connection/transient failure) |

Existing multipart/photo/AI errors retain their behavior. Public errors contain only code/message, never SQL, credentials, provider internals or stack traces. No replacement recommendation is generated on failure.

## Exact Postman workflow

1. POST `http://localhost:7070/api/v1/stories/recommend`.
2. Body > form-data: add `photos` as File for first.jpg, another `photos` File for second.jpg, and optional `prompt` Text (`Suggest a story`). Let Postman generate the multipart Content-Type. This step invokes the selected AI provider and may be billed.
3. A successful response has status RECOMMENDED. Choose a recommendation whose `renderPlan.layoutType` is SINGLE. Copy its `recommendationId`. The originals, recommendations and finalized plans have already committed to MySQL.
4. POST `http://localhost:7070/api/v1/stories/render`. Body > raw > JSON:

```json
{"recommendationId":"paste-the-actual-uuid-here"}
```

5. Set Content-Type to application/json. Send **no photos, assets or renderPlan**. Save the binary response as `story.png`; Postman can also preview the PNG. This step makes zero AI calls.

## Tests and operational limits

Run `.\mvnw.cmd clean verify` with JDK 25. Maven activates the test profile, which overrides datasource credentials/driver/URL with an isolated in-memory H2 database in MySQL compatibility mode and disables the cleanup schedule. Provider keys are placeholders and AI is mocked. Integration tests apply the same Flyway migration and exercise real JPA transactions, rollback, selected BLOB loading, JSON-only rendering, corruption handling and cleanup. Tests do not access java16. SpringBootTest classes also explicitly activate the `test` profile for safe IDE runs.

Docker was not running in the implementation environment, so native MySQL/Testcontainers execution was not available. H2 verifies application transactions and migration structure but does not establish native MySQL compatibility; perform the local startup/migration and Postman checks above against your MySQL server. No live AI calls or changes to your java16 tables were made during automated verification.

Image decoding retains Milestone 6's memory limits: large originals and concurrent renders still need sufficient JVM heap. BLOB reads are limited per image; storage capacity depends on upload volume within the retention window. MySQL BLOB storage is an MVP decision, not the planned permanent photo store.

This prototype has no authentication or authorization. A recommendation UUID currently grants access to its private stored photographs through rendering. **Do not expose this endpoint publicly** until authorization or secure access tokens are implemented. Keep the database local/private and protect backups and credentials. No new authentication system is included in this change.

## Implementation files and verification

Created in `src/main/java/com/storystylist/backend/storage/`:

- `StoredStoryRequest`, `StoredStoryPhoto`, `StoredRecommendation`: JPA entities.
- `StoryRequestRepository`, `StoryPhotoRepository`, `RecommendationRepository`: metadata/persistence repositories.
- `StoryPhotoBytesRepository`, `StoryPhotoBytesRepositoryImpl`: parameterized, size-bounded original-image reads within the JPA transaction.
- `StoryStorageService`: atomic saves, lookup/expiry/integrity checks and transactional cleanup.
- `StoredStoryRenderer`, `StoredPhotoFile`: stored-data adapters to the existing renderer.
- `StorageProperties`, `StorageConfiguration`, `StoryCleanupScheduler`, `StorageException`: retention, database/migration checks, scheduling and safe errors.

Also created: the V1 story migration, `application-test.properties`, `StoryStorageTests`, `StorageConfigurationTests`, and this guide.

Modified: `pom.xml`, local `application.properties`, `StoryRecommendationService`, `StoryRenderer`, `StoryRenderController`, `StoryRenderExceptionHandler`, README and the single-photo rendering guide. Existing recommendation/render MVC tests received storage mocks; four existing full-application test classes explicitly select the isolated test profile. The preprocessing and Java2D rendering implementations were not changed.

Verification command: `.\mvnw.cmd -B -Djava.awt.headless=true clean verify`. Full build: 301 tests, zero failures/errors/skips; the executable jar is produced. Build output is saved at `target/storage-clean-verify.log`. Native MySQL and paid AI calls were not run, so local database startup and the live recommend/Postman workflow remain manual checks.
