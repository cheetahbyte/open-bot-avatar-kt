# Changelog

## 0.3.0

- Align major and minor versions with upstream; native patch releases can have different patch numbers.
- Track Open Bot Avatar 0.3.0 at commit `5e64c081971eb296f562544641bad37d40fb36b6`; keep portable definition contract version 1.
- Port 0.2.1 smooth cross-sections, relative curve serialization, rounded side sections, and edge-aware slice selection.
- Separate six runtime states from neutral and happy moods; retain deprecated idle and thinking mood calls.
- Preserve the state clock through mood-only changes, including interrupted transitions and done-state mounting behavior.
- Morph numeric eye outlines between pills, arcs, and waiting slits.
- Add body shrink, replacement marks, and layered ribbons with silhouette-aware hoops and OKLCH color gradients.
- Render effects in SVG and Compose, with live theme colors and reduced-motion behavior.
- Regenerate the catalog and revision-tagged parity fixtures; expand JVM state, frame, eye, and Skia rendering tests.

### Upgrade from 0.1.0

Recompile consumers. Custom renderers must handle ribbon shapes and filled eye paths; poses use numeric eye looks.
Legacy idle and thinking mood calls remain supported but are deprecated. Thinking now displays three dots instead of the body.
The avatar definition contract remains version 1.

## 0.1.0

- Port the Open Bot Avatar 0.2.0 engine to Kotlin: validation, volume, poses, frames, and SVG output.
- Add `AvatarCatalog` with the upstream examples, gallery, and display colors.
- Add the `BotAvatar` Compose renderer with pointer following, reduced motion, and accessibility labels.
- Add parity fixtures generated from the TypeScript engine.
- Report infinite sizes in validation errors as upstream does, instead of garbled numbers.
- Aim the pointer gaze from the drawn eyes in non-square layouts.
- Give each `FramePath` one stable `box`. When rounded slices share a path, it no longer varies by frame.
- Reuse the validation's body analysis when preparing an avatar, instead of repeating it.
