# Changelog

## 0.5.0

- Track Open Bot Avatar 0.5.1 at commit `396073383830bf7d1c6b83316a9ff2111192df03`; keep portable definition contract version 1.
- Accept a custom state wherever a state is accepted: `AvatarStateDefinition`, `StateContext`, and `stateDefinition`. The six names are definitions too.
- List a pose's marks as values: `Pose.marks` replaces `Pose.bang` and `Pose.dots`. Add `AvatarMark`, `MarkContext`, `MarkPart`, and `PoseMark`.
- Hold host code to what can be drawn: unusable pose numbers, marks, and parts are replaced or left out, and a mark's outline is escaped in SVG.
- Add the material extension point to Compose: `AvatarMaterial`, with `material` on `BotAvatar` and `drawAvatarFrame`. No built-in materials ship yet.
- Make the volume model public for materials, and add `AvatarFrame.surface` and `FramePath.round`.
- Mark custom states, marks, and materials with `@ExperimentalOpenBotAvatarApi`, an opt-in at warning level.
- Build volumes about six times faster with upstream's 0.3.1 algorithm.
- Seat each flat eye in the tangent plane of the surface under it, and hide it at the limb, as upstream does.
- Keep a small body on screen when a change from one mark to another is interrupted, until a mark shows.
- Flatten arcs with the same bits as upstream, which settles ties in five side slices of the light bulb.
- Regenerate the catalog (palette, rocket, and witch hat changed) and the parity fixtures.

### Upgrade from 0.3.0

Recompile consumers. Calls that pass one of the six states compile unchanged.
`FrameOptions.state`, `PoseInput.state`, and `MoodState.state` now read as `AvatarStateDefinition?`; compare them by `id`.
Build poses with `marks` instead of `bang` and `dots`, which remain as deprecated, read-only properties.
Code that builds an `AvatarFrame` by hand needs the new `surface`. Custom renderers must escape a mark's `d` in markup.
The avatar definition contract remains version 1.

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
