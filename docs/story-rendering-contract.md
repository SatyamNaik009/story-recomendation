This document records the Milestone 5 compiler baseline. **Milestone 6 now implements SINGLE rendering, original-asset verification, adjustments, captions and PNG encoding.** See [the current single-photo rendering contract](single-photo-rendering.md) for executable behavior, endpoint usage and limits. Statements below about future implementation describe the Milestone 5 baseline.

# Story rendering contract — Milestone 5

## Implemented scope

`RenderSpecificationCompiler` deterministically converts a selected, finalized
`StoryRecommendation` plus `List<PhotoAssetMetadata>` into `CompiledStorySpecification`.
It accepts the recommendation envelope so existing summary/photo/caption consistency
validation can be reused without fabricating a summary. The summary itself is not
copied into the compiled specification.

Architecture:

```text
Existing one-call recommendation generation
  -> existing RenderPlanNormalizer (COVER + measured text fitting)
  -> validated StoryRenderPlan v3 and selected recommendation ID
  -> RenderSpecificationCompiler
  -> RenderSpecificationValidator
  -> CompiledStorySpecification (compiled-story-v1)
  -> FUTURE Java renderer + separately supplied original assets
  -> FUTURE 1080 x 1920 RGB PNG
```

Implemented: immutable specification records, compilation, metadata and geometry
validation, deterministic tests. Not implemented: decoding/drawing originals,
compositing, pixel adjustments, PNG encoding, asset-byte verification, storage,
payments, music, or rendering HTTP endpoints. Compilation does not call an AI,
re-run recommendations, or change photo selection. It contains no SDK dependency.

The compiler rejects unnormalized plans. It reuses RecommendationValidationService
for finalized v3 input, CoverCropResolver for the small reconciliation caused by
integer destination rounding, and TextLayoutResolver for strict text verification.
It does not duplicate RenderPlanNormalizer or normalize creative input.

## Fixed output and schema compatibility

Every compiled specification has canvas 1080 x 1920, format PNG, contentType
image/png and colorModel RGB. The future canvas is opaque. There is no caller/AI
choice of output format and no redundant rendering configuration bean for constants.

The only accepted legacy v3 renderingOptions are `{format:JPEG,jpegQuality:90}`.
Existing validation enforces that exact legacy combination. The compiler explicitly
translates this known compatibility marker to the new server-controlled PNG contract.
JPEG quality has no PNG meaning and is not copied into the specification. Missing or
unsupported legacy options are errors, not ignored settings. PNG encoding therefore
requires no new AI schema or model call. The existing v3 recommendation API is unchanged.

## Executable data model

`CompiledStorySpecification` contains specificationVersion, recommendationId,
canvas, output, background, globally ordered layers, and a selected-source asset
manifest. Layers use a closed IMAGE/TEXT discriminator. Lists are immutable snapshots.
No UUIDs, timestamps or random values are introduced by compilation.

* CompiledImageLayer: order, photoId, integer destination rectangle, continuous
  pixel sourceCrop, clockwise rotation, COVER fitMode, and existing adjustments.
* CompiledTextLayer: order, original caption, subpixel pixel box, supported font,
  resolved font size, color, alignment, rotation=0, and a deep-copied resolvedLayout.
* Manifest entries reuse PhotoAssetMetadata rather than duplicate its fields.
  Only images selected for this recommendation appear, in image layer order.

No bytes, Base64, SDK objects, MultipartFile, storage paths, URLs or natural-language
rendering instructions appear in compiled specifications. A photoId references the
manifest, not a filesystem location. Manifest validation is not proof of byte existence.

## Coordinate conversion and rounding

Top-left origin; x rightward, y downward. Canvas rectangles and source crops are
different spaces. For an image destination, round each absolute edge independently:

```text
left   = Math.round(x * 1080)
top    = Math.round(y * 1920)
right  = Math.round((x + width) * 1080)
bottom = Math.round((y + height) * 1920)
pixelWidth = right - left; pixelHeight = bottom - top
```

Edges are exclusive on the right/bottom. Java Math.round rounds half upward for
these non-negative coordinates. Never round width separately or accumulate rounded
widths: shared normalized edges map to the same pixel edge, avoiding accidental gaps.
Intentional design gaps remain. Rectangles collapsing to zero pixels are rejected.
Destination rectangles must stay inside the canvas. Unrelated edges are not snapped
together. Existing image non-overlap rules remain; text may overlay images.

Source crops retain double-precision pixel-edge coordinates. They must not be
converted to an integer subimage and independently stretched to the destination.
Subpixel crop sampling is necessary to retain exact aspect ratios on arbitrary
original dimensions. After rounding the destination, CoverCropResolver uses the
already-final crop as an immutable containment boundary, trimming only excess width
or height to reconcile the rounded destination ratio. The existing focal point is
preserved where possible, otherwise the current crop center is used. This is not
a new creative crop or a reinterpretation of the original preferred AI crop.

Multiply the reconciled normalized crop by the source dimensions AFTER EXIF
orientation and the extra selected rotation. Relative crop/destination aspect error
must be <=1e-9. Pixel crops use floating coordinates; the future renderer must sample
them proportionally, without a new arbitrary COVER crop. It should use a clipped
affine mapping with equal x/y scale. Bicubic resampling, with crop-edge clamping,
is the compiled-story-v1 resize convention; pixels outside the crop are not displayed.

## Originals and transformations

`OriginalPhotoAssets.openOriginal(photoId)` is the caller-supplied stream boundary
for a future renderer. It must provide original bytes, never processed AI thumbnails.
The renderer owns and closes the returned stream. No asset storage is implemented.

Before rendering, the future renderer must validate all required bytes against:

1. Their detected JPEG/PNG format, not upload MIME/filename.
2. SHA-256 equal to the manifest hash (hex comparison is case insensitive).
3. Original dimensions and existing upload/decode resource limits.
4. EXIF-oriented dimensions equal to manifest orientedWidth/orientedHeight.

The compiler validates hash syntax, positive dimensions, valid media type, and that
oriented dimensions are either unchanged or swapped. PNG follows the current pipeline's
unchanged dimensions. It cannot validate actual hashes or orientation tags without bytes.
EXIF mirrored/180-degree transforms cannot be inferred from dimensions alone: read the
original EXIF using the existing bounded reader and apply the complete orientation once.

The future compiled-story-v1 execution sequence is:

```text
verify original bytes -> decode -> EXIF orientation exactly once
-> additional clockwise 0/90/180/270 rotation -> final subpixel crop
-> proportional resize -> exposure -> contrast -> saturation
-> composite in layer order -> text -> encode opaque RGB PNG
```

The compiled contract fixes resize-before-adjustments for the future renderer. Earlier
v3 design notes described proposed crop/adjust/resize ordering, but no pixel adjustment
or rendering implementation existed. Parameter meanings/ranges are unchanged; this
new executable specification is authoritative for the future renderer's order.

Quarter turns swap EXIF-oriented source dimensions again; 0/180 do not. Coordinates
in sourceCrop refer to this final rotated source space. Do not rotate landscape images
automatically. Transparent original pixels composite onto white before RGB processing,
matching the existing prototype's declared alpha convention. The canvas background
itself uses the AI's selected SOLID #RRGGBB color.

Request-local IDs do not retrieve assets after a request ends. Future callers must
retain originals securely or reupload them and verify hashes. Missing originals are
not replaced with generated content or another uploaded photo.

## Adjustment semantics

Use normalized sRGB channels, not linear-light values. Alpha has already been resolved.
Apply exposure [-1,1] stops: channel *= 2^exposure (neutral 0). Apply contrast [.5,1.5]:
channel = (channel-.5)*contrast+.5 (neutral 1). Compute luminance
L=.2126R+.7152G+.0722B, then saturation [0,1.5]: channel=L+saturation*(channel-L)
(neutral 1). Clamp final channels to [0,1], then round to 8-bit channel values.
Do not clamp between operations. No generative editing or facial alterations.
Warmth, highlights, shadows, sharpness and other effects are unsupported.
These are specifications only; pixel algorithms remain unimplemented.

## Typography

TextPixelBox multiplies normalized coordinates by 1080/1920 without rounding. This
preserves the exact measured box and avoids invalidating wrapped lines at pixel edges.
Java2D supports fractional text positions. TextLine.xOffset and baseline remain in
pixels relative to the box top-left: canvas draw coordinates are box.x+xOffset and
box.y+baseline. Widths, padding, line spacing and original caption are preserved.

Use the existing SANS_SERIF -> Java logical SansSerif plain mapping, font size,
FontRenderContext (antialiasing and fractional metrics), paragraph/Unicode context,
and measurementVersion. Do not independently rewrap/refit text or add captions.
Strict validation reuses TextLayoutResolver; it never replaces stored line data.
Resolved lines are drawn in order, retaining paragraph shaping context where needed.

Compiler and renderer must share a pinned JDK/OS/font environment and algorithm.
Headless Java2D measurement is supported; arbitrary fonts, fallback/emoji rendering
and cross-host pixel identity are not promised. A future renderer should verify
font/glyph availability and compiled line metrics and fail safely on mismatch.
Absent captions produce no text layer. No decorations are synthesized.

## Ordering and errors

Background is first. Preserve v3's contiguous order values starting at zero: selected
images, then the optional caption. Duplicate/missing order values, unsupported layers,
image overlap, invalid crops, unknown/duplicate assets, non-finite values and out-of-bounds
geometry are rejected. Text-over-image overlap is intentional and allowed. No aesthetic
scoring or extra layout policy is introduced.

Compilation/validation failures throw InvalidRenderSpecificationException with a safe
internal rule identifier. There is no rendering HTTP endpoint or new public status-code
mapping yet. Existing recommendation errors and controllers are untouched. No fallback,
dummy spec or AI repair request is performed.

## Example conversion

Illustrative fixture, not a captured user response: source photo-1 has original and
EXIF-oriented dimensions 4000x2000. Its opaque hash is the recorded SHA-256. A selected
v3 image has destination `{x:0,y:.1,width:.5,height:.3}`, rotation 0, final crop
`{x:.5125,y:.1,width:.375,height:.8}`, and adjustments `.2,1.1,.9`.

The corresponding compiled fields are:

```json
{
  "specificationVersion": "compiled-story-v1",
  "recommendationId": "preserved-recommendation-id",
  "canvas": {"width": 1080, "height": 1920},
  "output": {"format": "PNG", "contentType": "image/png", "colorModel": "RGB"},
  "background": {"type": "SOLID", "color": "#102030"},
  "layers": [{
    "type": "IMAGE", "order": 0, "photoId": "photo-1",
    "destination": {"x": 0, "y": 192, "width": 540, "height": 576},
    "sourceCrop": {"x": 2050, "y": 200, "width": 1500, "height": 1600},
    "rotation": 0, "fitMode": "COVER",
    "adjustments": {"exposure": 0.2, "contrast": 1.1, "saturation": 0.9}
  }]
}
```

This excerpt omits the manifest: actual specifications also contain the selected
PhotoAssetMetadata entries with real recorded hashes. The compiler's regression fixture
uses current v3 normalized recommendations with synthetic IDs/hashes and no private data.
No captured successful user response was supplied; no production fixture is invented.

## Manual verification and next milestone

1. In PowerShell, use the existing Java 25 JAVA_HOME and run ` .\mvnw.cmd clean verify`.
   Tests run offline; no provider calls or generated Story files.
2. Run only compiler tests with ` .\mvnw.cmd -Dtest=RenderSpecificationCompilerTests test`.
3. Inspect `result` in these tests: output must be PNG/RGB, canvas 1080x1920, selected
   manifest only, image rectangle edges in bounds, and crop pixel ratio equal to destination.
4. Existing Postman flow remains POST http://localhost:7070/api/v1/stories/recommend,
   multipart repeated photos files plus optional prompt. It returns recommendations,
   not compiled specs or PNGs. Manual AI requests may incur charges.
5. A Java caller can invoke `compiler.compile(selectedRecommendation, response.assets())`.
   There is no public compile/render endpoint. Inspect the returned object in a debugger
   or unit test; do not print private captions/hashes to routine logs.
6. Negative tests demonstrate rejected unsupported options/geometry/assets. To diagnose
   a caller error, inspect the safe exception rule and correct the input contract.

Milestone 6 must implement original-byte verification/decode limits, EXIF application,
sampling, adjustment algorithms, compositing, typography and PNG encoding, with actual
visual/pixel regression tests. Font/runtime reproducibility and a rendering API still
need implementation. Music is postponed; no AI schema migration is needed for this milestone.
