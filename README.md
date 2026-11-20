# Open Bot Avatar for Android

A Kotlin and Jetpack Compose adaptation of [Open Bot Avatar](https://github.com/DominikScholz/open-bot-avatar).
An avatar is one SVG silhouette and an eye placement. The renderer adds volume, gaze, states, moods, and effects.

This library reads the same definitions as the TypeScript package and produces the same frames.
It tracks upstream version 0.3.0 at commit [`5e64c08`](https://github.com/DominikScholz/open-bot-avatar/commit/5e64c081971eb296f562544641bad37d40fb36b6).
The portable definition remains contract version 1.

## Modules

| Artifact | Contents | Depends on |
| --- | --- | --- |
| `re.leob.openbotavatar.kt:open-bot-avatar-core` | Validation, volume, poses, frames, and SVG output. Plain JVM, no Android. | `kotlinx-serialization-json` |
| `re.leob.openbotavatar.kt:open-bot-avatar-compose` | The `BotAvatar` composable and pointer tracking. | `core`, Compose UI |

Requirements: Android 7.0 (API level 24) or later, and Java 17 bytecode support.

## Add the library

The library isn't published to a Maven repository. Gradle can build it from this Git repository,
or you can include a checkout.

### Build from Git

This option needs no checkout, and Gradle clones the release tag for you. Add the repository to
your `settings.gradle.kts` file:

```kotlin
sourceControl {
    gitRepository(uri("https://github.com/cheetahbyte/open-bot-avatar-kt.git")) {
        producesModule("re.leob.openbotavatar.kt:open-bot-avatar-core")
        producesModule("re.leob.openbotavatar.kt:open-bot-avatar-compose")
    }
}
```

Then add the dependency to your app module:

```kotlin
dependencies {
    implementation("re.leob.openbotavatar.kt:open-bot-avatar-compose:0.1.0")
}
```

This option has the following limits:

- Gradle turns off the configuration cache for your whole build.
- You must set the `ANDROID_HOME` environment variable. Your project's `local.properties` file isn't used.
- You can pin a tag or a branch, but not a commit.

### Include a checkout

This option keeps the configuration cache and can pin any commit. Add the library as a Git submodule,
then include it in your `settings.gradle.kts` file:

```kotlin
includeBuild("third_party/open-bot-avatar-kt")
```

The included build doesn't read your project's `local.properties` file, so it needs its own Android
SDK location. Set the `ANDROID_HOME` environment variable, or add a `local.properties` file to the
library checkout.

Add the same dependency to your app module.

## Show an avatar

`BotAvatar` fills the largest centered square of its layout bounds, so give it a size:

```kotlin
BotAvatar(
    definition = AvatarCatalog.definition("mug")!!,
    modifier = Modifier.size(96.dp),
    color = MaterialTheme.colorScheme.primary,
    state = AvatarState.WORKING,
    mood = AvatarMood.HAPPY,
)
```

The avatar paints past its bounds while it turns and hops, so don't clip it tightly.

The first build of a definition runs in the background, and the avatar is empty until it finishes.
Later uses of a recent definition appear at once.

| Parameter | Behavior |
| --- | --- |
| `definition` | Required. Validate imported data first. |
| `color`, `eyeColor` | Compose colors. Theme colors work and update live. |
| `state` | `IDLE` (default), `THINKING`, `WORKING`, `WAITING`, `BLOCKED`, or `DONE`. Changes ease from the current pose. |
| `mood` | `NEUTRAL` (default) or `HAPPY`, independent of the state. Eyes morph between looks. |
| `stateAge` | Optional elapsed seconds in the state. Live avatars track this automatically; mood-only changes preserve the clock. |
| `depth` | Volume from 0 (flat) to 14. The default is 6. |
| `followPointer` | Look and turn toward touches and the mouse. The default is `false`. |
| `lookAt` | A fixed `LookDirection`, each axis from -1 to 1. Overrides the pointer. |
| `holdGaze` | Keep the current look direction. The default is `false`. |
| `view` | An extra `AvatarView` rotation in radians. |
| `reducedMotion` | `System` (default), `On`, or `Off`. `System` turns on when animations are turned off in the system settings. |
| `still` | Draw one calm frame and never animate. Use it for thumbnails and lists. |
| `label` | Accessible name. The default is the definition's name. `""` marks the avatar as decorative. |

### States and moods

Thinking replaces the body with three hopping dots. Blocked replaces it with an exclamation mark.
Working periodically sweeps ribbons around the body. Done celebrates with turns and ribbons, then rests before repeating.
Waiting has slit eyes; a happy mood replaces them with arcs. Happy idle avatars also hop.
Reduced motion freezes body motion and marks, stops blinking, and hides ribbons.

An avatar mounted in `DONE` starts at rest. A later transition into `DONE` starts its celebration immediately.
For deterministic exports, pass `FrameOptions(state = AvatarState.DONE, stateAge = 1.2)`.
Without `stateAge`, exported done avatars rest.

Existing `mood = AvatarMood.IDLE` and `mood = AvatarMood.THINKING` calls still work but are deprecated.
Replace them with `state = AvatarState.IDLE` or `state = AvatarState.THINKING`; use `AvatarMood.NEUTRAL` for the mood.
The legacy `AvatarMotion` option also remains available. Explicit state and mood take precedence over it.
Recompile consumers after updating: the expanded pose and frame models aren't binary-compatible with 0.1.0.

## Use the catalog

`AvatarCatalog` ships the upstream starter avatars and community gallery. `examples` and `gallery`
list them, `all` combines them, and `definition` finds one by ID. `color` returns the hex color that
upstream shows an avatar in. In Compose, `displayColor` returns the same color as a `Color`:

```kotlin
BotAvatar(
    definition = AvatarCatalog.definition("ghost")!!,
    modifier = Modifier.size(96.dp),
    color = AvatarCatalog.displayColor("ghost"),
)
```

## Follow the pointer across a screen

Without further setup, a `followPointer` avatar sees only touches inside its own box. To follow
touches anywhere, wrap the screen in `AvatarPointerArea`:

```kotlin
AvatarPointerArea(Modifier.fillMaxSize()) {
    BotAvatar(definition, Modifier.size(96.dp), followPointer = true)
}
```

`AvatarPointerArea` observes events and doesn't consume them, so scrolling and clicks are unaffected.

## Validate imported definitions

`validateAvatar` accepts JSON text, a `JsonElement`, or an `AvatarDefinition`. It returns the
normalized definition or a list of errors. The rules are in the
[avatar definition contract](https://github.com/DominikScholz/open-bot-avatar/blob/main/docs/avatar-contract.md).

```kotlin
when (val result = validateAvatar(text)) {
    is ValidationResult.Valid -> show(result.value)
    is ValidationResult.Invalid -> report(result.errors)
}
```

Unlike upstream, invalid JSON syntax returns `Invalid` instead of throwing an exception.

## Render without Compose

The `core` module works on any JVM. `PreparedAvatar.of` validates a definition and builds its volume.
Call it off the main thread, because the build is the expensive step. Frames are then cheap:

```kotlin
val avatar = PreparedAvatar.of(definition)
val svg = avatar.svg(FrameOptions(color = "#1fbf68", mood = AvatarMood.HAPPY), size = 256.0)
val frame = avatar.frame(FrameOptions(time = 1.2))
```

`AvatarFrame` lists paths and transforms from back to front, for custom renderers.
Draw slices in `color`, eyes in `ink`, and ribbon pieces with their user-space linear gradients.
Eyes can be pills, stroked arcs, or filled paths. Marks use ordinary slices.
The supplied matrices keep ribbons and marks independent of the body's root motion.
In Compose, `DrawScope.drawAvatarFrame` paints the frame.
When computing frames yourself, pass the same body color into `FrameOptions.color` and `drawAvatarFrame`.
Ribbon palettes are generated before painting.

To drive your own animation host, use `AvatarPlayer`, or use `AvatarAnimator` and `computePose` for finer control.
Keep one player per on-screen avatar. It preserves state age through mood changes and definition replacements.
Compose forwards your current body color before generating ribbon palettes, so gradients follow theme changes.

## Differences from the TypeScript package

- `AvatarState`, `AvatarMood`, and the legacy `AvatarMotion` use enum values instead of strings.
- Colors in `BotAvatar` are Compose colors, which replace CSS colors.
- `renderAvatarScene`, `sceneFrame`, and `renderer: "scene"` are left out because they target CSS 3D.
- `avatarExtent` and bitmap fitting are left out because Compose draws without an intermediate bitmap.
- The advisory `validate.sh --strict` checks (`faceRoom`, `silhouetteSeams`) are left out. They aren't part of the upstream public API.
- `PreparedAvatar` is public, so apps can build volumes on a background thread.

## Development

Building requires JDK 17 to 25 and Android SDK 36. The `compose` module fails on JDK 27.

```sh
./gradlew build
```

The tests run without an emulator:

- `ParityTest` compares validation, numeric eye outlines, full frames, poses, state clocks, and SVG output with TypeScript fixtures.
- `AvatarPlayerTest` checks legacy calls, state transitions, replacement marks, ribbon stacking, colors, and reduced motion.
- `BotAvatarRenderTest` draws the composable through Skia with Robolectric. Renders are saved in `compose/build/renders`.

To update to a new upstream version, regenerate `AvatarCatalogData.kt` and the fixtures. Replace
`UPSTREAM_PATH` with the path to an `open-bot-avatar` checkout:

```sh
node scripts/sync-upstream.ts UPSTREAM_PATH
./gradlew build
```

The script requires a Node.js version that runs TypeScript directly (22.18 or later).
Fixtures record the upstream package version and Git revision.
They cover all state and mood combinations, legacy options, interrupted transitions, and ribbon gradients.

## License

[MIT](LICENSE). Based on [Open Bot Avatar](https://github.com/DominikScholz/open-bot-avatar) by Dominik Scholz.
