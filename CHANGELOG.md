# Changelog

## 0.1.0

- Port the Open Bot Avatar 0.2.0 engine to Kotlin: validation, volume, poses, frames, and SVG output.
- Add `AvatarCatalog` with the upstream examples, gallery, and display colors.
- Add the `BotAvatar` Compose renderer with pointer following, reduced motion, and accessibility labels.
- Add parity fixtures generated from the TypeScript engine.
- Report infinite sizes in validation errors as upstream does, instead of garbled numbers.
- Aim the pointer gaze from the drawn eyes in non-square layouts.
- Give each `FramePath` one stable `box`. When rounded slices share a path, it no longer varies by frame.
- Reuse the validation's body analysis when preparing an avatar, instead of repeating it.
