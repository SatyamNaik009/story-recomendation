# AI Instagram Story Stylist backend

A modular monolith foundation for AI Instagram Story Stylist, built with Java 25,
Spring Boot 4.1.2, and Maven. The base package is `com.storystylist.backend`.

## SINGLE-photo presentation

Rendering now defaults to a full-context, larger SINGLE-photo composition with grouped caption spacing
and a muted photo-derived backdrop. Set `story.render.single.refinement-enabled=false` and restart
for the original presentation. Use the same unexpired recommendation ID to compare without another AI call.
See [SINGLE quality changes and comparison steps](docs/single-photo-quality.md).

## Current storage and rendering workflow

Recommendations now save original photos and finalized plans temporarily in MySQL **java16**.
Configure the local MySQL credentials in application.properties before starting the application.
The preferred render request is `POST /api/v1/stories/render` with JSON
`{"recommendationId":"<returned UUID>"}`; no reupload or additional AI call is needed.
The multipart render endpoint remains compatible, and rendering still supports SINGLE layouts only.
Retention defaults to 24 hours with automatic cleanup every 15 minutes.

See [temporary MySQL storage and Postman instructions](docs/temporary-mysql-storage.md)
for schema migrations, packet limits, transaction behavior, errors, privacy, and test setup.
Earlier milestone sections below describe the original incremental implementation.

## Prerequisites

- JDK 25, with `JAVA_HOME` pointing to its installation and its `bin` on `PATH`.
- Internet access for the Maven Wrapper and dependency downloads on the first build.
- Port 7070 available when running the application.
- Local MySQL with database `java16`, configured credentials and sufficient `max_allowed_packet` (see storage instructions).

Maven is provided by the checked-in wrapper; a separate Maven install is unnecessary.
Check `java -version` and `.\mvnw.cmd -version` to confirm both use Java 25.
Configure your IDE's project JDK as Java 25 too.

## Build and test

From the project root in Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
```

To run only the tests:

```powershell
.\mvnw.cmd test
```

Maven tests use the isolated H2 `test` profile and never your java16 database.
The tests cover application context startup, the health response (HTTP 200, JSON
content type and both fields), unsupported POST (405), and an unknown URL (404).
They use JUnit Jupiter and Spring Boot's managed test dependencies, including the
[Spring Boot 4 WebMvcTest API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/webmvc/test/autoconfigure/WebMvcTest.html).

## Run

```powershell
.\mvnw.cmd spring-boot:run
```

Alternatively, after a successful build:

```powershell
java -jar target/story-stylist-backend-0.0.1-SNAPSHOT.jar
```

The application name is `story-stylist-backend` and the configured port is `7070`.
Standard Spring Boot environment variables or command-line arguments can override configuration.

## Health check

`GET http://localhost:7070/api/v1/health` returns HTTP 200 with
`Content-Type: application/json`:

```json
{
  "status": "UP",
  "service": "story-stylist-backend"
}
```

Try it in a separate PowerShell terminal:

```powershell
Invoke-RestMethod http://localhost:7070/api/v1/health
```

This is a basic application smoke check. It does not check dependency readiness.

## Photo inspection (Milestone 1)

`POST http://localhost:7070/api/v1/photos/inspect` consumes `multipart/form-data`
and returns JSON. Send 1–10 files under the repeated field `photos`. The optional
text field `prompt` accepts up to 300 Unicode code points; blank or whitespace-only
text is treated as absent. Nonblank prompts are checked without trimming and are
not echoed in responses or logged.

Only JPEG and PNG content is accepted. Signatures and ImageIO-detected formats
must agree. Filenames, extensions, and client Content-Type values are ignored.
IDs (`photo-1`, `photo-2`, …) follow the order of the `photos` parts in the current
request. They are not persistent identifiers or storage URLs.

### Limits

| Setting | Default |
| --- | --- |
| `spring.servlet.multipart.max-file-size` | `10MB` (10,485,760 bytes) |
| `spring.servlet.multipart.max-request-size` | `110MB` (115,343,360 bytes, including framing) |
| `server.tomcat.max-swallow-size` | `128MB` (bounded draining of rejected uploads) |
| `photo.validation.max-photos` | `10` |
| `photo.validation.max-file-size-bytes` | `10485760` |
| `photo.validation.max-prompt-length` | `300` |
| `photo.validation.max-pixels` | `25000000` |
| `photo.validation.max-width` / `max-height` | `10000` each |

Configuration lives in `src/main/resources/application.properties`. Validation
limits are checked at startup and can be tightened; count, file size, and prompt
limits cannot exceed the endpoint's documented ceilings. A file must satisfy both
the per-axis and total-pixel limits. Pixel multiplication uses `long` arithmetic.
Spring's [multipart configuration](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/servlet/autoconfigure/MultipartProperties.html)
enforces request and file size before controller processing.

Tomcat drains rejected bodies up to 128MB so typical oversized uploads receive
the structured 413 response instead of a connection reset. A client sending beyond
that drain ceiling may see its connection closed; the upload acceptance limits
remain 10MB per file and 110MB per request.
For clients using `Expect: 100-continue`, a small Tomcat customizer delays the
interim response until the body is read. This allows oversized declared requests
to be rejected before transmission (Tomcat's
[`onRead` behavior](https://github.com/apache/tomcat/blob/main/webapps/docs/config/http.xml)).

### Postman

1. Create a POST request to `http://localhost:7070/api/v1/photos/inspect`.
2. Select **Body → form-data**.
3. Add `photos`, change its type from Text to **File**, and select a JPEG or PNG.
4. Repeat the `photos` row for additional files (maximum ten).
5. Optionally add a **Text** row named `prompt`.
6. Let Postman generate the Content-Type header and multipart boundary; send the request.

PowerShell alternative (replace the sample paths with your files):

```powershell
curl.exe -X POST http://localhost:7070/api/v1/photos/inspect -F "photos=@C:/photos/first.jpg" -F "photos=@C:/photos/second.png" -F "prompt=Summer memories"
```

Example HTTP 200 response (actual sizes and dimensions depend on your files):

```json
{
  "status": "VALID",
  "photoCount": 2,
  "promptProvided": true,
  "photos": [
    {"photoId": "photo-1", "mediaType": "image/jpeg", "sizeBytes": 245678, "width": 1080, "height": 1920},
    {"photoId": "photo-2", "mediaType": "image/png", "sizeBytes": 384921, "width": 1200, "height": 1600}
  ]
}
```

Example HTTP 400 response:

```json
{"code":"INVALID_IMAGE","message":"Only valid JPEG and PNG images are supported"}
```

| HTTP status | Error codes |
| --- | --- |
| 400 | `MISSING_PHOTOS`, `TOO_MANY_PHOTOS`, `EMPTY_PHOTO`, `INVALID_IMAGE`, `INVALID_DIMENSIONS`, `INVALID_PROMPT`, `INVALID_MULTIPART` |
| 413 | `UPLOAD_TOO_LARGE` (file or request limit) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` (request must be multipart/form-data) |

The entire request is rejected if any photo is invalid. Errors contain only
`code` and `message`; no parser exceptions, filenames, or filesystem paths.

### Request flow and resource handling

Postman → Spring multipart parser → `PhotoUploadController` →
`PhotoValidationService` → ImageIO header inspection → immutable response records
→ Spring JSON serialization. The controller delegates; the service checks count
and prompt, then processes one file at a time, assigns its ID, checks size and
signature, reads dimensions, and maps metadata to a record. An exception handler
maps validation and multipart errors to safe JSON, including parsing failures
that occur before controller selection.

The inspection endpoint does not save photos or return bytes/Base64. The servlet container
may spool uploads to temporary storage and removes them during normal request
cleanup. ImageIO uses an in-memory header cache; streams are closed and readers
disposed on success and failure. No custom temporary files are created.

`VALID` means the supported signature, readable dimensions, and configured limits
passed inspection. Header inspection does not fully decode pixels, guarantee an
intact compressed payload, detect malware, or prove that an image is a photograph.
Use the Milestone 2 endpoints below for decoding and preprocessing. AI analysis has not occurred.

### Tests

```powershell
.\mvnw.cmd clean verify
```

Service tests use generated JPEG/PNG images and cover ordering, metadata, count
and prompt boundaries, spoofing, corrupt signatures, unreadable headers, oversized
files, excessive dimensions, and stream cleanup. MockMvc tests exercise controller
mapping with the real service. HTTP integration tests start the embedded server
on a random port and exercise actual multipart parsing, ten-file uploads,
malformed multipart bodies, the configured 10MB file and 110MB request limits,
and the existing health endpoint. No AI provider or external service is required.

If `JAVA_HOME` still selects an older JDK, select your Java 25 installation before
running Maven. For the JDK installed on this workstation:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.5.1'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd -version
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

## Scope and roadmap

Milestone 0 provides application startup and the health endpoint. Milestone 1
adds photo inspection and validation. Milestone 2 adds image preprocessing and
a development JPEG preview. There is no permanent storage, database, authentication,
music recommendation or story recommendation generation. Milestone 3 adds Gemini
and OpenAI visual analysis through the existing preprocessing service. Request
concurrency controls, operational metrics, and final story recommendations remain future work.

## Photo preprocessing (Milestone 2)

Preprocessing gives a future multimodal consumer consistently oriented, bounded,
compressed JPEG inputs without changing the original upload. The initial
1568 x 1568 bounding box is a configurable product choice that balances visual
detail, decode/transfer cost, and memory use. It is not a claimed requirement or
token/pricing guarantee of any AI provider. Milestone 3 integrates providers separately below.

### Pipeline and architecture

Postman -> Spring multipart parser -> preprocessing/preview controller ->
`PhotoPreprocessingService` -> existing `PhotoValidationService` -> ImageIO
header/EXIF inspection and subsampled decode -> Thumbnailator orientation and
resize -> white RGB compositing -> ImageIO JPEG encoding -> metadata JSON or
binary JPEG response.

Validation runs once for the whole request before decoding: existing formats,
signatures, photo count, file size, dimension/pixel limits, and prompt rules are
reused. Each validated photo is then processed sequentially. Original dimensions
mean the stored raster dimensions; processed dimensions reflect display orientation.
Request-local IDs and upload order are preserved. A failure rejects the whole request.

The photo module now contains:

```text
photo/
  config/      PhotoProcessingProperties (plus existing upload/validation configuration)
  controller/  PhotoPreprocessingController, PhotoPreviewController, PhotoUploadController
  dto/         ProcessedPhoto, PhotoPreprocessingMetadata, PhotoPreprocessingResponse
               (plus existing inspection DTOs)
  service/     PhotoPreprocessingService, JpegExifOrientationReader, PhotoValidationService
  exception/   PhotoProcessingException (plus existing validation errors and shared handler)
```

`preprocess(photos, prompt)` returns a list of short-lived `ProcessedPhoto` records
containing metadata and actual encoded bytes. Future services can call it directly
without internal HTTP requests. No `MultipartFile` or `BufferedImage` is stored in
the result. Consumers must treat its byte array as read-only; access does not make
repeated copies. The bytes are excluded from JSON serialization as an extra guard,
and the metadata endpoint maps to a separate response record.

### Orientation and resizing

The bounded EXIF reader inspects only JPEG APP1 IFD0 orientation, validates TIFF
byte order, magic, offsets, field type, and count, and supports both endian orders.
It does not follow thumbnail or GPS directories. This avoids relying on the
fixed field-layout assumptions in Thumbnailator 0.4.21's EXIF parser.
Thumbnailator's supported
[`ExifFilterUtils`](https://javadoc.io/static/net.coobird/thumbnailator/0.4.21/net/coobird/thumbnailator/util/exif/ExifFilterUtils.html)
applies orientations 2–8, including mirrored and transposed variants. Orientation
is applied once, before final resizing; automatic EXIF transformation is disabled
for the already-oriented buffered image.

Missing, malformed, unsupported, or ambiguous orientation metadata falls back to
stored pixel order and `orientationCorrected=false`. That flag is true only when
a recognized non-normal orientation was actually applied; false is not a guarantee
that an untagged image was photographed upright. Orientation 1 and PNG inputs also
return false. PNG orientation tags are not interpreted in this milestone.

Both display dimensions fit the configured box. The scale is the smaller of 1,
maximum-width/display-width, and maximum-height/display-height. Final dimensions
are rounded to the nearest integer, with a minimum of one pixel. No cropping or
intentional aspect-ratio change is performed; integer rounding can introduce a
subpixel aspect difference. Subsampling never reduces the decoded image below
the target dimensions. Final resizing uses Thumbnailator's high-quality default
resizer; explicit target dimensions avoid deriving a different ratio from the
rounded subsampled raster.

| Stored dimensions | EXIF orientation | Processed dimensions |
| --- | --- | --- |
| 4000 x 3000 | absent/1 | 1568 x 1176 |
| 3000 x 4000 | absent/1 | 1176 x 1568 |
| 800 x 600 | absent/1 | 800 x 600 |
| 4032 x 3024 | 6 | 1176 x 1568 |

### Processing configuration

| Property under `story.photo.processing` | Default | Allowed values |
| --- | --- | --- |
| `max-width` | `1568` | 1–10000 |
| `max-height` | `1568` | 1–10000 |
| `jpeg-quality` | `0.85` | Greater than 0 and at most 1; finite |
| `output-format` | `jpeg` | `jpeg` only |
| `allow-upscaling` | `false` | `false` only for this milestone |
| `preview-enabled` | `false` in typed defaults; `true` in this local prototype's properties | `true` / `false` |

Invalid processing configuration prevents application startup. Input validation
and multipart ceilings remain unchanged. JPEG output is always freshly encoded
at the configured quality; PNG alpha is composited onto white, including partially
transparent pixels. Original EXIF, GPS, comments, and source metadata are not
passed to the JPEG writer. The resulting JPEG contains the normal encoder headers
but no copied source EXIF orientation, preventing a second rotation by viewers.
Fresh output uses an RGB raster; source color-profile preservation is not promised.

Disable the development preview outside local development:

```properties
story.photo.processing.preview-enabled=false
```

Or set `STORY_PHOTO_PROCESSING_PREVIEW_ENABLED=false`. The preview route then
returns 404; preprocessing and inspection remain available.

### Preprocessing API and Postman

1. Start the app using the Java 25 commands above.
2. Create **POST** `http://localhost:7070/api/v1/photos/preprocess`.
3. Choose **Body -> form-data**.
4. Add **File** rows named `photos`, one per JPEG/PNG (1–10).
5. Optionally add a **Text** row named `prompt` (existing 300-character rule).
6. Let Postman create the multipart Content-Type/boundary. Send the request.
7. Inspect the HTTP 200 JSON response; bytes and Base64 are never included.

```powershell
curl.exe -X POST http://localhost:7070/api/v1/photos/preprocess -F "photos=@C:/photos/first.jpg" -F "photos=@C:/photos/second.png" -F "prompt=Summer memories"
```

Illustrative response (actual sizes and dimensions depend on the uploads):

```json
{
  "status": "PROCESSED",
  "photoCount": 1,
  "photos": [
    {
      "photoId": "photo-1",
      "originalWidth": 4032,
      "originalHeight": 3024,
      "processedWidth": 1176,
      "processedHeight": 1568,
      "processedMediaType": "image/jpeg",
      "processedSizeBytes": 198420,
      "orientationCorrected": true
    }
  ]
}
```

### Preview API and Postman

1. Create **POST** `http://localhost:7070/api/v1/photos/preview`.
2. Choose **Body -> form-data** and add exactly one **File** row named `photo`.
3. Select a JPEG/PNG and send. The prompt field is not part of this endpoint.
4. View the JPEG in Postman's response preview, or use **Send and Download**.

The response is actual `image/jpeg` bytes with Content-Length,
`Content-Disposition: inline; filename="preview.jpg"`, `Cache-Control: no-store`,
and `X-Content-Type-Options: nosniff`. It never uses the uploaded filename.
The preview uses the same service and transformation pipeline as preprocessing.

```powershell
# The server does not store this image; --output saves it on your client by request.
curl.exe -X POST http://localhost:7070/api/v1/photos/preview -F "photo=@C:/photos/first.jpg" --output preview.jpg
```

Errors retain the existing `{ "code", "message" }` JSON convention, including
preview errors when the client requested JPEG. Invalid/corrupt input or decoding
warnings return 400 `INVALID_IMAGE`; multiple preview photos return 400
`INVALID_PHOTO_COUNT`. Existing missing-photo, prompt, 413 upload-size, and 415
content-type handling remain in effect. Unexpected transformation/encoding errors
return 500 `IMAGE_PROCESSING_FAILED` without internal details.

### Resource safety, tests, and limitations

ImageIO reads source dimensions and verifies they match the validated input
before allocating a raster. The validated 25-million-pixel ceiling still applies.
Integer source subsampling reduces the output raster of large decodes, followed
by final resizing; decoder internals may still scan the entire compressed file
and allocate additional buffers. Decoding warnings are conservatively rejected,
including JPEG truncation that ImageIO might otherwise repair.

Streams use try-with-resources; ImageReader/ImageWriter and graphics contexts are
disposed, and temporary raster references are released after each image. ImageIO
uses explicit memory caches, not application-created temporary files. Multipart
temporary storage still follows normal servlet request cleanup. The original
upload is read without modification and is available during the request only;
preserving it does not imply persistent storage.

Memory is **not constant**: one decoded photo and its transformation buffers,
compressed input caching, and accumulated JPEG results consume memory. Multiple
simultaneous requests multiply this cost. There is no parallel processing within
a batch, global cache, or permanent storage. Subsampling can lose fine detail;
quality/performance and concurrent request budgets should be measured before
production deployment. Validation and decoding are not a malware scanner.

Run all tests and package the application:

```powershell
.\mvnw.cmd clean verify
```

Milestone 2 tests use generated images, including real JPEG APP1 EXIF fixtures.
They verify every orientation from asymmetric colored pixels, non-default TIFF
offsets, malformed metadata fallback, landscape/portrait/square/odd dimensions,
no enlargement, JPEG quality effects, white alpha flattening, metadata stripping,
no double correction, original byte preservation, ordered IDs, corrupted pixel
data, stream cleanup, and invalid startup configuration. Real HTTP tests verify
both new endpoints, JPEG decoding, response metadata/headers, upload errors, and
the preview disable switch. Existing health and inspection tests remain required.

## Multimodal visual analysis (Milestone 3)

`POST http://localhost:7070/api/v1/photos/analyze` accepts the same repeated
`photos` file field (1–10 JPEG/PNG images) and optional `prompt` text field
(300 Unicode code points). This endpoint sends processed photographs to the
selected external provider. Inspection, preprocessing, preview, and health remain
local and require no API credentials.

### Architecture and request flow

Multipart controller -> `PhotoAnalysisService` -> existing validation/preprocessing
-> aggregate payload guard -> injected `VisionAiClient` -> one provider SDK request
-> strict JSON parsing -> shared semantic validation -> `PhotoAnalysisResponse`.

`VisionAiClient` exposes `provider()` and `analyze(List<ProcessedPhoto>, String)`.
Its response contains a shared `VisionAnalysisResult`, provider-reported model
identifier, and nullable `AiUsageMetadata`. Shared records are `PhotoObservation`
and `GroupAnalysis`; SDK objects stay within `ai/provider` and SDK configuration.
`AiConfiguration` constructs exactly one adapter using a small enum switch.
The analysis service and controller have no provider-specific branches.

New code is organized under `ai/config`, `ai/domain`, `ai/provider`, `ai/prompt`,
`ai/validation`, and `ai/exception`, plus the analysis controller/service/DTO in
the existing photo module. Existing photo processing code is reused unchanged.

Official dependencies (pinned and resolved by Maven):

- [`com.google.genai:google-genai:2.4.0`](https://github.com/googleapis/java-genai):
  Gemini Developer API `models.generateContent`, with the `v1beta` endpoint.
- [`com.openai:openai-java:4.106.0`](https://github.com/openai/openai-java):
  Responses API with strict JSON Schema output and `store=false`.

There is no orchestration framework, permanent provider file upload, image
generation, or internal HTTP call between services.

### Provider configuration

| Property | Default |
| --- | --- |
| `story.ai.provider` | `gemini` (allowed: `gemini`, `openai`) |
| `story.ai.gemini.api-key` | `YOUR_GEMINI_API_KEY` (local placeholder) |
| `story.ai.gemini.model` | `gemini-3.6-flash` |
| `story.ai.openai.api-key` | `YOUR_OPENAI_API_KEY` (local placeholder) |
| `story.ai.openai.model` | `gpt-6.1-terra` |
| `story.ai.timeout-seconds` | `90` (1–300) |
| `story.ai.max-retries` | `1` (0–3 additional attempts) |
| `story.ai.max-image-payload-bytes` | `12582912` (12 MiB; may be lowered) |
| `story.ai.max-output-tokens` | `8192` (256–16384) |

For local development, edit `src/main/resources/application.properties` directly
and restart the application. No API-key or provider environment variables are required.
`@ConfigurationProperties` binds provider, models, and keys to `AiProperties`.
Legacy `GEMINI_API_KEY` and `OPENAI_API_KEY` lookups have been removed. Standard
Spring property precedence still applies: remove stale run-configuration/environment
overrides such as `STORY_AI_PROVIDER` if they override your file edits.

Only the selected provider's bound `api-key` is used. Missing, empty, blank, or either
`YOUR_GEMINI_API_KEY` / `YOUR_OPENAI_API_KEY` placeholder is treated as missing
(also ignoring surrounding whitespace and placeholder case). Unused-provider
placeholders do not prevent startup. Key fields are excluded from JSON serialization
and redacted in configuration `toString()` output. Keys are never explicitly logged.
The Gemini client explicitly selects the Developer backend and receives the Gemini
key; it does not inherit a Google Cloud/Vertex backend or another provider's key.
The OpenAI client uses explicit credentials, with SDK request logging and telemetry
disabled. An unused provider is never instantiated.

The app starts even without the selected key so local endpoints stay usable;
analysis then returns 503 `AI_CONFIGURATION_ERROR`. Invalid provider names or
invalid numeric/model settings fail startup. Model access, billing, region support,
and model lifecycle remain account-dependent; no live availability is claimed by
offline tests.

### Local credential safety

This workspace was not a Git repository when checked; there was no index to change.
`src/main/resources/application.properties` is now excluded by `.gitignore` for
future normal Git adds. `application.properties.example` contains placeholders only;
copy it to `application.properties` if the local file is absent, then edit your local
copy. Existing local key values are preserved by this configuration update.

If this file is already tracked in another checkout, adding it to `.gitignore`
does **not** stop Git from tracking changes. Check `git ls-files` before entering
secrets and arrange an approved index change first; no index or history changes
were made here. Do not force-add the local file. Build output and executable JARs
embed application properties, so do not share artifacts built with real keys.

**No automatic fallback:** a Gemini failure never sends photographs to OpenAI,
and vice versa. One analysis invokes one selected adapter once. Its SDK may retry
transient failures up to the configured count, always against that same provider.
Authentication and ordinary invalid requests are not retried. Google SDK attempts
are configured as `max-retries + 1`; OpenAI uses `maxRetries`. A timeout is a provider
HTTP-operation setting, not an end-to-end upload deadline. Retries, backoff, and
local preprocessing can make overall latency exceed 90 seconds.

### Image transmission, prompt, and output validation

All processed images are included in one multimodal request in original order,
each immediately preceded by its stable `Photo ID: photo-N` text part. Gemini
receives `Part.fromBytes(..., "image/jpeg")` inline data; the SDK serializes Base64.
OpenAI receives `data:image/jpeg;base64,...` image URLs within a user message.
Original filenames and original EXIF/GPS metadata are not sent. No external image
URL or permanent Files API upload is created.

The shared aggregate cap is **12 MiB of actual JPEG bytes**, checked before provider
invocation and again at the adapter boundary. Base64 expands this to about 16 MiB,
leaving room for text/schema framing under the conservative 20 MB inline request
budget. Requests over the cap return 413 without contacting either provider.
The same conservative cap applies to both adapters.

`VisualAnalysisPrompt` is versioned as `visual-analysis-v1`. It requests visible
subjects/surroundings, lighting, composition, palette/atmosphere, strengths and
weaknesses, common themes, and relationships across the set. User text is sent as
JSON-quoted untrusted context, separately from system instructions. Text inside
images is also treated as untrusted. The instructions forbid sensitive-attribute
inference, assumptions about language/music preferences, captions, layouts, songs,
generated images, and final story concepts. Prompt instructions reduce risk but
do not guarantee factual accuracy or eliminate prompt injection.

Both providers receive the explicit schema in
`src/main/resources/ai/vision-analysis-v1.schema.json`. Required fields are:

- `overallMood`
- `photos[]`: `photoId`, `description`, `lighting`, `composition`,
  `visualStrengths[]`, `visualWeaknesses[]`
- `groupAnalysis`: `commonTheme`, `complementaryPhotoIds[]`, `observations`

Unknown JSON fields, duplicate JSON keys, trailing content, malformed JSON, oversized
responses, refusals, and incomplete generations are rejected. Shared validation
requires every input ID exactly once, nonblank bounded text, bounded lists, and
only known nonduplicate complementary IDs. Valid observations are reordered to
input order. Invalid output is never replaced with fake analysis.

Success adds `status=ANALYZED`, actual adapter `provider`, actual response `model`
(Gemini falls back to the requested model if its version is omitted), `photoCount`,
`latencyMillis`, `usage`, and `promptVersion`. Latency covers the SDK invocation
including retries, excluding preprocessing. Usage counts come only from SDK
metadata; absent usage or individual counts are `null`. Gemini total tokens can
include thinking tokens, so input plus visible output need not equal the reported
total. Usage is not a calculated invoice and token totals are not necessarily
comparable across providers.

Illustrative response only; actual observations and counts come from the provider:

```json
{
  "status": "ANALYZED",
  "provider": "GEMINI",
  "model": "gemini-3.6-flash",
  "photoCount": 1,
  "overallMood": "Warm and relaxed",
  "photos": [{
    "photoId": "photo-1",
    "description": "A sunlit garden table",
    "lighting": "Soft natural light",
    "composition": "Table in the foreground",
    "visualStrengths": ["Clear subject"],
    "visualWeaknesses": []
  }],
  "groupAnalysis": {
    "commonTheme": "Outdoor relaxation",
    "complementaryPhotoIds": [],
    "observations": "Only one photograph was supplied"
  },
  "latencyMillis": 1200,
  "usage": null,
  "promptVersion": "visual-analysis-v1"
}
```

### Errors

| HTTP | Code | Meaning |
| --- | --- | --- |
| 503 | `AI_CONFIGURATION_ERROR` | Missing/invalid selected key, unsupported model, or provider request configuration |
| 503 | `AI_PROVIDER_UNAVAILABLE` | Connection failure or provider server failure |
| 504 | `AI_PROVIDER_TIMEOUT` | Provider timeout |
| 429 | `AI_RATE_LIMITED` | Provider rate limit or quota exhaustion |
| 502 | `AI_INVALID_RESPONSE` | Invalid JSON, refusal/incomplete generation, or invalid photo references/fields |
| 413 | `AI_PAYLOAD_TOO_LARGE` | Aggregate processed JPEG budget exceeded |

Existing 400 photo/prompt validation, 413 multipart upload limits, and 415 request
content-type handling remain unchanged. Errors contain only `code` and a safe
`message`; provider bodies, headers, API keys, and internal exceptions are not exposed.

### Billing, free-tier limits, and privacy

Google AI Pro subscription benefits and Gemini API project billing are separate.
A subscription is not unlimited Gemini API usage. Eligible subscription/developer
benefits may include Cloud credits that must be activated and applied under their
own conditions; check the [Developer Program benefits](https://developers.google.com/profile/help/benefits)
and [Gemini billing documentation](https://ai.google.dev/gemini-api/docs/billing).

Gemini's [pricing page](https://ai.google.dev/gemini-api/docs/pricing) lists free-tier
availability by model. Free-tier access is limited by requests per minute, input
tokens per minute, and requests per day, applied per project. Exact quotas vary
by model, project, and tier; view the project's active limits in
[AI Studio](https://aistudio.google.com/rate-limit) and the
[rate-limit documentation](https://ai.google.dev/gemini-api/docs/rate-limits).
This app does not hardcode or promise a universal free RPM/RPD allowance.

Under Google's [unpaid-service terms](https://ai.google.dev/gemini-api/terms), inputs
and outputs may be used to improve products and reviewed by humans. Do not use
unpaid services for sensitive/confidential photographs; review paid-service terms
and regional exceptions for your project. EXIF stripping does not anonymize faces,
locations, documents, or other personal information visible in pixels.

OpenAI requests set `store=false`, but that does not itself guarantee zero retention
or disable all abuse-monitoring logs. Review the account's
[OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data).
Transmit only images you are authorized to send, and avoid SDK HTTP debug logging.
The API remains a local unauthenticated prototype; exposing it publicly can allow
others to incur API costs. Provider usage and retries may be billable, including
requests whose returned output fails our validation. Set budgets in provider consoles.

### Manual live tests (never run by Maven)

The automated suite uses fake keys, mocks, and loopback-only mock provider servers.
No live provider request has been performed as part of this implementation.
The real Gemini success acceptance criterion remains pending a manual request;
the optional OpenAI live criterion also remains unverified.

For **one Gemini request**, edit the local `application.properties`: set
`story.ai.provider=gemini`, replace the Gemini key placeholder with your key,
and retain `story.ai.gemini.model=gemini-3.6-flash`. The unused OpenAI placeholder
can remain. Set `story.ai.max-retries=0` for a single-attempt live smoke test.
Do not paste keys into Java code, documentation, or chat. Start the app:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.5.1'
.\mvnw.cmd spring-boot:run
```

In Postman:

1. POST `http://localhost:7070/api/v1/photos/analyze`.
2. **Body -> form-data**: add 1–10 **File** rows named `photos`.
3. Optionally add a **Text** row named `prompt`; leave it absent to test independent analysis.
4. Let Postman generate the multipart Content-Type/boundary; send once.
5. Check HTTP 200, `provider=GEMINI`, the returned model, exact photo IDs/count,
   observations, latency, and nullable usage. A successful JSON shape alone does
   not establish visual accuracy; compare descriptions with the actual photographs.

For **one optional OpenAI request**, stop the app, set `story.ai.provider=openai`
in the same file, replace the OpenAI key placeholder, and retain
`story.ai.openai.model=gpt-6.1-terra`. Then restart:

```powershell
.\mvnw.cmd spring-boot:run
```

Send once in Postman and check `provider=OPENAI`. This requires an account with
model access and available API billing/credits; Gemini credentials are unnecessary.
After manual tests, restore `story.ai.max-retries=1` in the file if desired.
There is no cross-provider fallback.

### Offline verification and future evaluation

```powershell
.\mvnw.cmd clean verify
```

Tests cover actual SDK serialization through local fake provider HTTP boundaries,
all JPEG bytes/IDs in one request, common schemas, `store=false`, reported usage,
rate/auth/server errors, timeouts, bounded retries, missing/placeholder credentials,
property-based provider selection and SDK key binding, strict parsing, semantic ID validation, real preprocessing,
and multipart controller behavior. Existing Milestone 0–2 tests remain enabled.

For future provider comparisons, use the same consented photo sets, prompt version,
and preprocessing configuration in separate deliberate runs. Compare grounded
visual accuracy, relationship quality, latency, and provider-specific usage/cost.
No comparison automation, final story recommendation, music selection, or image
generation is implemented in Milestone 3.

Implementation verification: Java 25 `clean verify` passed with 154 tests, zero
failures/errors/skips, and a packaged executable JAR. This verifies offline
behavior, not real provider access or visual-analysis quality. Live tests remain
manual and unverified as described above.



## Milestone 4: recommendations and render-ready plans

`POST /api/v1/stories/recommend` accepts multipart `photos` (1?10 JPEG/PNG)
and optional `prompt` (up to 300 characters). Existing endpoints remain available.

The thin controller invokes StoryRecommendationService, which uses existing
validation/preprocessing, records original-image metadata, then invokes the single
configured VisionAiClient.generate operation. Gemini GenerateContent and OpenAI
Responses receive the same strict StorySchema and versioned story-recommendation-v3
instructions. There is no preliminary analysis, repair call, or provider fallback.
Existing bounded SDK retries still apply to transient failures; ?one call? means
one logical SDK generation operation, not a promise of one HTTP attempt when retries
are enabled. SDK initialization, provider selection, model names and keys are unchanged.

The response contains status RECOMMENDED, server requestId, provider, actual model,
photoCount, recommendations, assets, SDK latencyMillis, nullable usage and promptVersion.
Each recommendation has a server-generated UUID, summary and renderPlan. Show only
summary to users: title, selectedPhotoIds, concept, selectionReason, layoutSummary,
caption {include,text}, whyItWorks. Technical plans and hashes belong to application
state, not Instagram messages. The model supplies 1?3 alternatives and may select
only a subset of uploads. IDs do not imply upload-order ranking.

### Closed visual contract: story-render-plan-v3

Canvas is 1080x1920 (9:16), background SOLID with opaque #RRGGBB color.
Layout types SINGLE, SPLIT_TWO, COLLAGE_THREE, COLLAGE_FOUR require 1, 2, 3, 4
selected images respectively. `layers.images` and `layers.texts` are separate typed
arrays, avoiding provider-specific polymorphism. Their shared `order` values must be
contiguous starting at zero, with images before caption. Image boxes cannot overlap.
All selected images occur exactly once. RenderingOptions is {format:JPEG,jpegQuality:90}.

Image layer fields: photoId, order, x, y, width, height, crop {x,y,width,height},
rotation, fitMode COVER, nullable focalPoint {x,y}, and adjustments {exposure,contrast,saturation}. Origin is top-left, x right,
y down. Destination values are canvas fractions; crop values are image fractions.
All values must be finite and boxes must remain inside their coordinate space.

Transformation order: decode original; apply EXIF orientation including mirroring;
apply AI-selected clockwise rotation (0/90/180/270); crop in that post-rotation
coordinate space; apply adjustments; resize preserving aspect ratio; place; draw text;
encode JPEG. Quarter turns swap source dimensions. Java finalizes COVER crops before
returning the plan; final physical aspect ratios agree within relative tolerance 1e-9.
The future renderer consumes the resolved crop without creative replanning or stretching. Landscape shape alone is not evidence for corrective rotation.
Use normalized rectangle edges multiplied by dimensions, rounded to nearest pixel,
with exclusive right/bottom edges. V2 has no decorative rotation, borders or effects.

Limited adjustments (no invented filters): exposure [-1,1] stops, neutral 0;
contrast [.5,1.5] multiplier, neutral 1; saturation [0,1.5] multiplier, neutral 1.
Future v2 implementation uses normalized sRGB channels: multiply by 2^exposure,
then apply (channel-.5)*contrast+.5, then saturation around luminance
.2126R+.7152G+.0722B; clamp final channels to [0,1]. Transparent originals composite
onto white before these operations, matching preview conventions. No body/face edits.
Warmth, highlights, shadows and sharpness are intentionally unsupported in v2.

Optional caption uses exactly one matching TextLayer: text, order, x/y/width/height,
font SANS_SERIF (Java logical SansSerif, plain), preferred fontSize, opaque #RRGGBB,
alignment LEFT/CENTER/RIGHT, rotation 0. Captions can contain explicit newlines and
remain limited to 120 characters. Text boxes stay inside y=.1.. .9. Java resolves
wrapping and font size using actual Java2D metrics, then returns resolvedLayout.
Caption include=false requires empty text and no text layers. Captions are never
deleted or rewritten to make a plan pass validation.

Shared deterministic validation checks required fields, supported enums/version,
counts, IDs, colors, adjustments, ordering, bounds, non-overlapping images, aspect
ratios and caption consistency. Unknown JSON properties, unsupported operations and
exact duplicate plans are rejected as AI_INVALID_RESPONSE (502), without repair.
Semantic aesthetics and whether alternatives are meaningfully different still require
product evaluation; validation does not pretend to measure artistic quality.
Existing upload 400/413/415 and AI 429/502/503/504 mappings are reused.

### Original assets and future rendering

Assets preserve upload order with request-local photoId, SHA-256 of ORIGINAL bytes,
original dimensions, detected mediaType, and exact EXIF-oriented dimensions.
The bounded existing EXIF reader is reused; the preprocessing algorithm is unchanged.
No bytes/Base64 are returned, no files or recommendations are permanently stored.
Originals are not modified. The plan cannot recreate unavailable photos: future
rendering must retrieve securely stored originals or receive reuploads and verify
hashes. Store the chosen recommendation/plan and asset mapping before enabling payment.
No additional creative AI call is necessary after selection. Implement rendering,
font/glyph checks, reproducible runtime/assets, persistence and payments separately.
Future verified music metadata can be added to the recommendation envelope without
changing the visual plan version. Music selection is Milestone 5; no music is selected,
downloaded or embedded here.

### Postman

1. Start the existing app on port 7070 with the selected provider configured locally.
2. POST http://localhost:7070/api/v1/stories/recommend.
3. Body ? form-data: add repeated `photos` keys of type File; attach 1?10 JPEG/PNG files.
4. Optionally add text `prompt`, e.g. A quiet afternoon. Let Postman set Content-Type.
5. Send manually (may incur provider charges). Expect RECOMMENDED and 1?3 recommendations,
   each with summary, UUID and populated renderPlan.layers.images; assets contains all uploads.
6. To compare providers, change story.ai.provider and restart, then repeat manually.

No live provider verification is performed by automated tests. Offline tests cover
SDK multimodal serialization, shared story schema, original hashes and orientation,
1/2/10-file multipart processing, subset selection, UUIDs, one generation invocation,
provider errors, invalid output and geometry/caption constraints. Existing APIs retain
regression coverage. Future work includes real-photo aesthetic evaluation and renderer
implementation; no rendering, storage, payments, Instagram or music functionality is added.


### Recommendation failure diagnostics

INFO logs now identify STORY_RECOMMENDATION versus VISUAL_ANALYSIS operations.
Recommendation start/completion/failure logs share a server-generated request ID
(which is also returned on success). Failure stages are PHOTO_PROCESSING,
PROVIDER_RESPONSE, RESPONSE_METADATA, JSON_PARSING, RENDER_PLAN_VALIDATION,
COVER_GEOMETRY_RESOLUTION, or FINAL_RENDER_PLAN_VALIDATION.
Plan failures include fixed rule identifiers such as image.cropDestinationAspectRatio,
image.destination.width.finiteOrRange, images.overlap, caption.textMismatch or text.fit.
These logs contain no supplied field values, raw responses, exception messages,
image content or user prompts. Provider-envelope rejection has a separate safe log.
The public error schema/status codes and validation thresholds are unchanged.
Use the failure stage/rule to diagnose output; do not disable validation or make
additional AI repair calls. Request correlation context is restored after each request.


### Deterministic COVER geometry (render-plan v2)

This is a breaking contract update: v1/v2 plans are not accepted by this recommendation
validator. Both provider schemas require fitMode=COVER and allow focalPoint=null.
The versioned prompt is story-recommendation-v3. No model, keys, retries, timeouts,
photo processing or analysis API configuration changes accompany this update.

In the raw AI response, crop means the **preferred** crop. The recommendation service
validates references, supported schema/fit/rotation, bounds, finite positive geometry,
focal points and all non-geometric rules before normalization. CoverCropResolver then
replaces crop with the final resolved rectangle and validates the finalized plan.
In the public API response, crop is always the authoritative **resolved** crop. The
preferred crop is not separately returned; the resolver checks containment against it
before replacing it. Future renderers must consume crop, not choose another crop.

Coordinates for both crop and focalPoint refer to the full source AFTER EXIF
orientation (including any mirroring), then the declared clockwise 0/90/180/270
rotation. They are not relative to the preferred crop. Metadata already contains
EXIF-oriented original dimensions; quarter-turn rotation swaps these once more.
No EXIF preprocessing behavior is changed. Null focalPoint means preferred-region center.

Let W,H be post-rotation source dimensions and dw,dh normalized destination dimensions.
Target ratio T=(dw*1080)/(dh*1920). Preferred ratio R=(crop.width*W)/(crop.height*H).
If R>T, keep height and set width=crop.height*H*T/W. If R<T, keep width and set
height=crop.width*W/(T*H). This trims excess content and never enlarges the region.
Position each axis at focalCoordinate-resolvedSize/2, clamped between preferredStart
and preferredEnd-resolvedSize. A focal point outside the preferred region is valid;
placement goes as near as possible without expanding the region. The resolver cannot
preserve a full face/subject bounding box from a point alone, so preferred-crop choices
still matter. It does not guarantee that an entire subject survives a narrow crop.

Final physical aspect ratios must match within relative 1e-9 (floating-point precision,
not a visual-distortion allowance). An already matching crop is returned unchanged,
which makes repeated resolution idempotent. All calculations use double precision.
Malformed geometry, unknown IDs, unsupported modes, non-finite values, and invalid
rotation remain controlled AI_INVALID_RESPONSE errors. No extra AI call repairs output.

Rendering will eventually need pixel-edge rounding/resampling; normalized geometry
is exact within the stated tolerance, not a guarantee of identical pixels on every
renderer. Actual image rendering, persistent assets, payments and music are out of scope.


### Measured text layout and centralized normalization (render-plan v3)

The former text.fit heuristic (character count times font size, and 1.5 times font
size for height) ran even in preliminary validation. It is replaced with measured
layout after normalization. No text-fit requirement is bypassed in final validation.

RenderPlanNormalizer now owns this sequence: basic/schema/reference/raw-coordinate
validation; unchanged COVER crop resolution; text resolution; construction of the
final immutable plan; strict final validation. StoryRecommendationService invokes
this one path. No additional provider operation occurs. Independent text-fitting
failures across recommendations are collected into safe rule identifiers; structural
errors still fail early because dependent calculations cannot safely continue.

TextLayoutResolver uses Java logical SansSerif, plain, a FontRenderContext with
antialiasing and fractional metrics enabled, TextLayout, and LineBreakMeasurer with
Locale.ROOT line breaking. It works without a display in headless mode. Available
pixels are normalizedWidth*1080 and normalizedHeight*1920, minus four pixels of
padding on each side. It measures both glyph ink bounds and advance width, including
negative bearings, and uses measured ascent/descent/leading. Between lines it adds
10% of measured ascent+descent to the font's leading. Text is top-aligned inside
padding, with per-line LEFT/CENTER/RIGHT horizontal alignment.

Wrapping honors explicit LF, CRLF and CR line breaks (original caption text is
preserved). Empty lines consume measured line height. Java wraps at word boundaries,
falling back to character boundaries for long words. Unsupported control/format
characters and glyphs absent from the logical font are rejected, not substituted
silently. Unicode supported by the configured runtime/font can be measured; arbitrary
emoji, complex fallback/color-emoji behavior and arbitrary external fonts are not
promised. Pin the JDK, OS/font environment and measurement algorithm for reproducible
rendering; Java logical font mappings can differ across hosts. No fonts are downloaded.

Optional configuration (independent of all provider settings):

```properties
story.render.text.min-font-size=24
story.render.text.max-font-size=72
```

The defaults preserve the previous supported range. Configuration permits
8 <= min <= max <= 144; invalid limits fail startup. The configured range is supplied
to both providers. The resolver first wraps at the preferred size, then tries each
smaller integer size down to the minimum and selects the largest fitting size.
It never enlarges text, changes the box/alignment, deletes captions or changes words.
An impossible layout remains AI_INVALID_RESPONSE with text.fit. Unsupported glyphs
use text.unsupportedGlyph. Strict final validation remeasures the finalized caption
at its resolved size and verifies its stored line data exactly.

TextLayer now includes Java-generated resolvedLayout: measurementVersion
java2d-sans-serif-v1, paddingPixels=4, lineSpacing=1.1, and ordered lines containing
text, xOffset, baseline and width in pixels relative to the box top-left. fontSize in
the returned layer is the resolved size. The model schema excludes resolvedLayout:
the AI supplies only creative input and must not invent line coordinates. Both provider
schemas/prompts use story-recommendation-v3 and render-plan-v3. Existing v1/v2 plans
must be migrated rather than silently interpreted as v3.

The future renderer must use the same logical font, FontRenderContext, paragraph
measurement/wrapping and baseline convention, retain paragraph context for complex
scripts, and verify line geometry against the pinned runtime. It draws from the
resolved plan without another AI call. Identical inputs in the same supported runtime
produce identical layouts; normalization is idempotent. This milestone measures text
only: actual image/text rendering, persistence, music and payments remain unimplemented.

## Milestone 5: executable rendering specification

A deterministic Java compiler now converts finalized v3 recommendations into validated 1080 x 1920 RGB PNG specifications. It does not draw or encode images and introduces no endpoint or AI call. See [the rendering contract](docs/story-rendering-contract.md) for the data model, pixel conventions, original-asset requirements, typography, output compatibility, and manual test instructions. Music is postponed while rendering work takes priority.


## Milestone 6: render a single-photo Story

POST /api/v1/stories/render now produces an actual 1080 x 1920 PNG from a finalized SINGLE plan and reuploaded originals, without an AI call. Send multipart text fields renderPlan and assets, optional recommendationId, and repeated photos files in their original order. All hashes/metadata are verified; only the selected original is decoded. See [single-photo rendering and Postman instructions](docs/single-photo-rendering.md). Multiple-photo rendering remains reserved for Milestone 7.

