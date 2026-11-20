> The preferred API now accepts JSON containing only recommendationId and retrieves stored originals. See [temporary MySQL storage](temporary-mysql-storage.md). The multipart workflow below remains supported.

# Milestone 6: single-photo rendering

Implemented endpoint: `POST /api/v1/stories/render`, multipart/form-data.
This endpoint makes no AI requests. Existing recommendation generation is unchanged
and still may recommend multiple-photo layouts; only SINGLE can currently be rendered.

## Postman workflow

1. Obtain a successful `/api/v1/stories/recommend` response using your normal uploads.
2. Select a recommendation whose `renderPlan.layoutType` is `SINGLE`.
3. Create POST `http://localhost:7070/api/v1/stories/render`.
4. Body -> form-data:

| Key | Type | Value |
|---|---|---|
| renderPlan | Text | The entire selected `recommendations[n].renderPlan` JSON object, including resolvedLayout when present |
| assets | Text | The entire top-level `assets` JSON array from that same response |
| recommendationId | Text, optional | The selected recommendation's ID |
| photos | File, repeated | ALL original files, in their original recommendation upload order |

5. Paste JSON directly, not a quoted/escaped JSON string, and do not paste the whole
   recommendation response into renderPlan. Let Postman set the multipart boundary.
6. Send. A successful response is binary PNG, `Content-Type: image/png`,
   `Content-Disposition: inline; filename="story.png"`, 1080x1920, with no-store caching.
7. View the image preview or use Postman's response Save/Download action (or Send and
   Download where available) and save as `story.png`. Error responses remain JSON.

There is no database. You must retain the original files and recommendation response.
Recompressed/resized photos, screenshots or preview JPEGs are not acceptable originals.
Reupload the full set even if only photo-2 is selected; sending one file does not
implicitly remap it to photo-2. Multipart file order maps to photo-1, photo-2, etc.
Assets must be in that order and have the same count. All original hashes, detected
formats and dimensions are verified; only the selected file is decoded to pixels.
IDs/hashes are integrity checks against the supplied metadata, not signed provenance
or permanent storage identifiers. Authentication/persistence remain out of scope.

## Execution

StoryRenderController -> StoryRenderer -> strict existing Jackson codec -> existing
header/file/pixel validation -> OriginalPhotoAssetResolver -> existing specification
compiler -> Java2DStoryRenderer -> StoryImageEncodingService -> PNG response.

The small compiler `compilePlan` adapter derives structural selected-ID/caption fields
from the supplied plan to reuse its existing envelope validation. Its internal fixed
labels are not creative output and are not returned. Geometry compilation is unchanged.
Unnormalized or altered invalid plans are rejected; rendering never repairs them with AI.

The selected source is decoded at original resolution. The bounded existing EXIF
reader and Thumbnailator orientation filters are reused, applied exactly once before
the additional declared 0/90/180/270 rotation. Alpha is flattened onto white as defined
by the prototype contract; Java2D converts into an RGB raster. Decode warnings are
treated as invalid image content rather than silently accepting repaired/truncated JPEGs.

Java2D uses the compiled destination rectangle and continuous crop. A floor/ceil
enclosing source view bounds interpolation support, and a single uniform affine
scale/translation maps the finalized crop into the clipped destination. This is not
a new center crop. Bicubic interpolation follows Java2D's sampling rules at the
enclosing pixel boundary; the fractional crop remains the geometric boundary.
No independent x/y stretching is performed.

After resizing, exposure multiplies normalized sRGB channels by 2^exposure; contrast
applies (channel-.5)*contrast+.5; saturation interpolates around
.2126R+.7152G+.0722B. Final channels clamp to [0,1] and round to bytes. No intermediate
clipping or additional filters occur. Neutral 0/1/1 settings bypass pixel adjustments.
The solid canvas background is taken from the plan, not hardcoded black.

Layer order follows the compiled specification. Optional text uses the original
caption, resolved font size, color, box and line positions. TextLayoutResolver exposes
an exact replay of its existing paragraph-measurement algorithm to obtain drawable
Java2D TextLayouts, preserving shaping context. It verifies the saved layout first;
it does not choose another font size, change words or independently rewrap text.
Drawing uses stored box-relative offsets/baselines, antialiasing and fractional metrics.
Absent captions produce no text. Runtime/font mismatches fail safely.

PNG is encoded with an explicit ImageIO PNG writer and in-memory output stream,
without copying source metadata. Input canvas dimensions and the full PNG signature
are checked. Tests decode the PNG again and verify relevant pixels. Legacy JPEG/90
plan options remain the explicitly supported input marker; output is always PNG/RGB.

## Errors

All errors retain the `{code,message}` JSON shape. Existing photo-validation and
multipart errors retain their status codes (including 400/413/415).

* 400: missing/invalid JSON, missing metadata/photos, incorrect ordered set, hash or
  metadata mismatch, invalid plan/specification, invalid selected image, incompatible text.
* 422 `RENDER_LAYOUT_UNSUPPORTED`: any layout other than SINGLE; extra image layers
  in a claimed SINGLE plan are invalid and never silently discarded.
* 500: unexpected rendering failure or PNG encoding failure, with sanitized messages.

No raw images, hashes, paths, provider details or stack traces are added to application
logs or public errors. No filesystem/URL image-source parameters are supported.

## Memory and determinism

Existing limits remain: 1–10 photos, up to 10 MiB per file, maximum dimensions and
25-million-pixel safety checks. Unselected originals are streamed for hashes and header
metadata only. A selected 25 MP image can require roughly 100 MiB per RGB/int raster;
decode/orientation/RGB conversion can temporarily hold several such buffers (roughly
300 MiB plus decoder/upload overhead), and concurrent requests multiply that usage.
Canvas/tile buffers are bounded by 1080x1920. This is a synchronous local prototype;
provide adequate heap and avoid excessive concurrent maximum-sized requests.

All image state is request-local. Graphics objects/readers/writers/streams are disposed,
rasters released after use, and user images are not retained in singleton services or
written to permanent storage. Servlet-managed multipart temporary files follow existing
Spring settings. Tests write only a synthetic output example under target/render-samples.

Pixel reproducibility requires the same JDK, fonts and Java2D/ImageIO environment.
Unsupported glyphs/font metric changes are rejected; arbitrary emoji/fonts and advanced
typography are not promised. No live provider requests are made by tests.

Run ` .\mvnw.cmd clean verify` with Java 25. Rendering tests include `StoryRenderTests`
and `StoryRenderControllerTests`, using synthetic colors and asymmetric landmarks,
all EXIF orientations, rotation, crop, adjustments, captions and source integrity.
`target/render-samples/single-photo-caption.png` is a synthetic visual inspection artifact,
not a recommendation or a user photograph.

Milestone 7 will add SPLIT_TWO and three-/four-photo compositing. No music, payments,
Instagram integration, persistence, authentication or additional AI calls are included.
