# SINGLE-photo presentation refinement (v1)

The previous renderer executed the AI's coordinates and final crop exactly. The normalizer could only shrink inside the preferred crop, never recover excluded edges. A small destination rectangle, a distant caption and a solid background therefore produced sparse output even when technically valid.

## What changes

The render path first compiles/validates the original plan. With refinement enabled, it derives a new in-memory SINGLE presentation, recompiles it through the same validators, and uses the existing Java2D renderer. Invalid source plans are still rejected. The saved recommendation, summary and v3 render-plan JSON are not modified.

- The foreground uses the whole EXIF-oriented original, with any existing additional rotation. It is fitted proportionally into a 972-pixel-wide area (54-pixel minimum side margins) and available vertical space. No stretching or generated content is used.
- The former preferred crop no longer constrains this optional presentation. This deliberately restores surrounding context and people near the sides. The focal point remains in the plan; since the entire image is shown, there is no need to crop tightly around it. This is geometry, not face/subject detection. Existing compiler rounding can still trim a very small fractional edge to match integer destination dimensions.
- Image and caption form one vertically centered group, with a 40-pixel gap. An above-photo caption stays above; otherwise it goes below. Caption safe zones remain enforced. No caption is added when absent.
- The exact caption text, alignment and font family are retained. Preferred size is restrained to 40?52 pixels within configured font limits, then measured/wrapped by the existing text resolver. Low-contrast colors are adjusted toward black or white against the AI-selected background.
- A small 54 x 96 copy of the real photo is cover-scaled and blurred three times, then blended at only 16% opacity over the original background color. This soft background fills otherwise empty regions without competing with the full-context foreground. It is not image generation. Background cropping affects only this blurred decorative copy, never the main photograph.
- Photo selection, stable ID, original bytes, rotation and exposure/contrast/saturation settings remain unchanged. Metadata/hash checks and selected-photo loading remain intact.

Unusually long captions or extreme image ratios that cannot fit safely retain the original validated geometry. Landscape photos necessarily leave more vertical space than portraits when preserving their entire frame; the backdrop softens that space rather than stretching or cropping the foreground to fill it.

No public DTO, AI schema, database migration, provider integration or endpoint change is needed. No AI call occurs during rendering. Multi-photo rendering remains unsupported.

## Compare using the same saved recommendation

Use an unexpired SINGLE recommendation ID so the AI result and photo stay identical.

1. Set `story.render.single.refinement-enabled=false` in application.properties and restart.
2. POST `/api/v1/stories/render` with JSON `{"recommendationId":"your-existing-uuid"}`. Save the PNG as `before.png`.
3. Set the property to `true` (the default), restart, and send the exact same request. Save as `after.png`.
4. Compare edge subjects/context, foreground size, image/caption spacing and background. Both outputs are 1080 x 1920. No photo reupload or new AI recommendation is needed.

The legacy multipart render route uses the same switch. Low-level compiled-specification rendering retains its exact execution contract; the refinement is an explicit application-layer step.

## Files and tests

Added `SinglePhotoLayoutRefiner`, `SinglePhotoPresentationProperties`, and `SinglePhotoBackdrop` in the render service package. Modified `StoryRenderer` to validate/refine/recompile and `Java2DStoryRenderer` to support the optional backdrop while retaining its legacy two-argument method. Added the configuration switch to local application.properties.

`SinglePhotoPresentationTests` covers context restoration from tight crops, edge markers, focal-point preservation, landscape/portrait/rotated geometry, increased scale, caption anchors/contrast, idempotency, deterministic PNG output, disabled-mode parity, and rejection of invalid inputs. Existing pixel-exact renderer tests explicitly use legacy mode so they continue checking the unchanged low-level renderer. MVC wiring includes the new refiner; stored-ID integration tests exercise the enabled default and verify zero AI calls.

Synthetic regression images are generated at `target/render-samples/single-before.png` and `single-after.png`. These are geometric fixtures, not user photographs; visual assessment with the user's original photos remains a manual step.

Run `.\mvnw.cmd clean verify` with Java 25. No live AI or user MySQL requests are required for automated tests.

Verification completed: clean verify succeeded with 312 tests, zero failures/errors/skips. The executable jar was built; target/single-quality-verify.log contains the build output. The synthetic before/after PNGs were visually inspected. No paid AI calls or user-database changes were made.
