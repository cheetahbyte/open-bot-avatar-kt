# Open Bot Avatar for Android

A Kotlin and Jetpack Compose port of [Open Bot Avatar](https://github.com/DominikScholz/open-bot-avatar) by Dominik Scholz.
An avatar combines an SVG silhouette with eye placement. The renderer adds volume, gaze, states, moods, and effects.

This port reads the same definitions and produces the same frames as upstream **0.3.0** at
[`5e64c08`](https://github.com/DominikScholz/open-bot-avatar/commit/5e64c081971eb296f562544641bad37d40fb36b6).
The portable definition remains **contract version 1**.

## Modules

| Artifact | Contents | Depends on |
| --- | --- | --- |
| `re.leob.openbotavatar.kt:open-bot-avatar-core` | Validation, volume, poses, frames, and SVG output. Plain JVM, no Android. | `kotlinx-serialization-json` |
| `re.leob.openbotavatar.kt:open-bot-avatar-compose` | The `BotAvatar` composable and pointer tracking. | `core`, Compose UI |

Requires Android 7.0 (API level 24) or later and Java 17 bytecode support.
Build with JDK 17–25 and Android SDK 36. The Compose module fails on JDK 27.

## Add the library

The library isn't published to a Maven repository. Use a pinned checkout for the features documented here:

1. From your app's root directory, clone and check out the implementation:

   ```sh
   git clone https://github.com/cheetahbyte/open-bot-avatar-kt.git third_party/open-bot-avatar-kt
   git -C third_party/open-bot-avatar-kt checkout aa95598a5c2533cd249d0e458e7af9743e29b004
   ```

2. Include the checkout in your app's `settings.gradle.kts`:

   ```kotlin
   includeBuild("third_party/open-bot-avatar-kt")
   ```

3. Add the dependency to your app module:

   ```kotlin
   dependencies {
       implementation("re.leob.openbotavatar.kt:open-bot-avatar-compose:0.1.0")
   }
   ```

Gradle uses the included checkout; `0.1.0` is its current module version, not a new release.
The older Git tag `0.1.0` tracks upstream 0.2.0 and doesn't include these features.

This setup preserves configuration-cache support. Set `ANDROID_HOME` or create `third_party/open-bot-avatar-kt/local.properties` with the SDK location.
The included build doesn't read your app's `local.properties`.

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

Don't clip the avatar tightly: it paints past its bounds while turning and hopping.
The first build of a definition runs in the background; the avatar stays empty until it finishes. Recent definitions use cached preparation.

| Parameter | Behavior |
| --- | --- |
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

`AvatarCatalog.examples` and `gallery` contain upstream starter avatars and community definitions; `all` combines them, and `definition(id)` finds one.
`color(id)` returns the upstream hex color. In Compose, use `color = AvatarCatalog.displayColor("ghost")` for its `Color` equivalent.

### States and moods

Thinking replaces the body with three hopping dots; blocked uses an exclamation mark.
Working periodically sweeps ribbons around the body. Done celebrates with turns and ribbons, then rests before repeating.
Waiting has slit eyes; a happy mood replaces them with arcs. Happy idle avatars also hop.
Reduced motion freezes body motion and marks, stops blinking, and hides ribbons.

An avatar mounted in `DONE` starts at rest; transitioning into `DONE` starts its celebration immediately.
For deterministic exports, pass `FrameOptions(state = AvatarState.DONE, stateAge = 1.2)`. Without `stateAge`, exported done avatars rest.

Legacy `mood = AvatarMood.IDLE` and `mood = AvatarMood.THINKING` calls still work but are deprecated.
Replace them with `state = AvatarState.IDLE` or `state = AvatarState.THINKING`; use `AvatarMood.NEUTRAL` for the mood.
`AvatarMotion` remains available, but explicit state and mood take precedence.
Recompile consumers: the expanded pose and frame models aren't binary-compatible with the `0.1.0` release.

## Follow the pointer across a screen

By default, `followPointer` sees only touches inside the avatar's box. To track the whole screen, wrap it in `AvatarPointerArea`:

```kotlin
AvatarPointerArea(Modifier.fillMaxSize()) {
    BotAvatar(definition, Modifier.size(96.dp), followPointer = true)
}
```

`AvatarPointerArea` observes events and doesn't consume them, so scrolling and clicks are unaffected.

## Validate imported definitions

`validateAvatar` accepts JSON text, a `JsonElement`, or an `AvatarDefinition` and returns a normalized definition or errors.
See the [avatar definition contract](https://github.com/DominikScholz/open-bot-avatar/blob/5e64c081971eb296f562544641bad37d40fb36b6/docs/avatar-contract.md):

```kotlin
when (val result = validateAvatar(text)) {
    is ValidationResult.Valid -> show(result.value)
    is ValidationResult.Invalid -> report(result.errors)
}
```

Unlike upstream, invalid JSON syntax returns `Invalid` instead of throwing an exception.

## Render without Compose

The `core` module works without Android. Call `PreparedAvatar.of` off the main thread to validate and build the volume once:

```kotlin
val avatar = PreparedAvatar.of(definition)
val svg = avatar.svg(FrameOptions(color = "#1fbf68", mood = AvatarMood.HAPPY), size = 256.0)
val frame = avatar.frame(FrameOptions(time = 1.2))
```

`AvatarFrame` lists paths and transforms from back to front. For custom renderers:

- Draw slices in `color`, eyes in `ink`, and ribbons with their user-space linear gradients.
- Handle pill eyes, stroked arcs, and filled eye paths. Marks use ordinary slices.
- Apply the supplied matrices to keep ribbons and marks independent of the body's root motion.

In Compose, `DrawScope.drawAvatarFrame` paints the frame. Pass the same body color to it and `FrameOptions.color`, because ribbon palettes are generated before painting.
`BotAvatar` handles this automatically, including live theme changes.

Use `AvatarPlayer` for your animation host, or `AvatarAnimator` and `computePose` for finer control.
Keep one player per on-screen avatar to preserve state age through mood changes and definition replacements.

## Differences from the TypeScript package

- `AvatarState`, `AvatarMood`, and the legacy `AvatarMotion` use enum values instead of strings.
- `renderAvatarScene`, `sceneFrame`, and `renderer: "scene"` are left out because they target CSS 3D.
- `avatarExtent` and bitmap fitting are left out because Compose draws without an intermediate bitmap.
- The advisory `validate.sh --strict` checks (`faceRoom`, `silhouetteSeams`) are left out. They aren't part of the upstream public API.

## Build and test

Build and run the tests without an emulator:

```sh
./gradlew build
```

- `ParityTest` compares validation, eye outlines, frames, poses, state clocks, and SVG output with versioned TypeScript fixtures.
- `AvatarPlayerTest` checks legacy calls, transitions, replacement marks, ribbon stacking, colors, and reduced motion.
- `BotAvatarRenderTest` uses Skia and Robolectric; renders are saved in `compose/build/renders`.

To update upstream data, regenerate `AvatarCatalogData.kt` and fixtures with Node.js 22.18 or later.
Replace `UPSTREAM_PATH` with the path to a clean checkout of the linked upstream revision:

```sh
node scripts/sync-upstream.ts UPSTREAM_PATH
./gradlew build
```

Fixtures record the upstream version and Git revision and cover every state and mood, legacy options, interrupted transitions, and ribbon gradients.

## License and attribution

[MIT](LICENSE). Port of [Open Bot Avatar](https://github.com/DominikScholz/open-bot-avatar) by Dominik Scholz.
