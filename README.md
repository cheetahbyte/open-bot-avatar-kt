# Open Bot Avatar for Android

A Kotlin and Jetpack Compose port of [Open Bot Avatar](https://github.com/DominikScholz/open-bot-avatar) by Dominik Scholz.
An avatar combines an SVG silhouette with eye placement. The renderer adds volume, gaze, states, moods, and effects.

This port reads the same definitions and produces the same frames as upstream **0.5.1** at
[`3960733`](https://github.com/DominikScholz/open-bot-avatar/commit/396073383830bf7d1c6b83316a9ff2111192df03).
The portable definition remains **contract version 1**.

Major and minor versions follow upstream. Patch releases can include native-only fixes, so patch numbers can differ.
The linked upstream revision identifies the engine used by this release. See the [changelog](CHANGELOG.md) for upgrade notes.

## Modules

| Artifact | Contents | Depends on |
| --- | --- | --- |
| `re.leob.openbotavatar.kt:open-bot-avatar-core` | Validation, volume, poses, frames, and SVG output. Plain JVM, no Android. | `kotlinx-serialization-json` |
| `re.leob.openbotavatar.kt:open-bot-avatar-compose` | The `BotAvatar` composable and pointer tracking. | `core`, Compose UI |

Requires Android 7.0 (API level 24) or later and Java 17 bytecode support.
Build with JDK 17–25 and Android SDK 36. The Compose module fails on JDK 27.

## Add the library

The library isn't published to a Maven repository. Use a checkout of the `0.5.0` release:

1. From your app's root directory, clone and check out the implementation:

   ```sh
   git clone https://github.com/cheetahbyte/open-bot-avatar-kt.git third_party/open-bot-avatar-kt
   git -C third_party/open-bot-avatar-kt checkout 0.5.0
   ```

2. Include the checkout in your app's `settings.gradle.kts`:

   ```kotlin
   includeBuild("third_party/open-bot-avatar-kt")
   ```

3. Add the dependency to your app module:

   ```kotlin
   dependencies {
       implementation("re.leob.openbotavatar.kt:open-bot-avatar-compose:0.5.0")
   }
   ```

Gradle uses the included release checkout for the declared dependency.

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
| `state` | `IDLE` (default), `THINKING`, `WORKING`, `WAITING`, `BLOCKED`, `DONE`, or a [custom state](#custom-states). Changes ease from the current pose. |
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
| `material` | The body's finish. The default, `null`, is a flat fill. See [Materials](#materials). |

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
Recompile consumers: the pose, frame, and state types aren't binary-compatible with the `0.3.0` release.

### Custom states

The six names are one vocabulary. If your bot has a status they don't cover, pass a definition instead of a name.
A definition is an `id` and a function that returns a whole pose, built from `ctx.base` or `ctx.rest()`:

> **Experimental.** Custom states, marks, and materials are written against the renderer's own model.
> Parts of it might be renamed or removed in a minor release. The six names are stable.
> Opt in with `@OptIn(ExperimentalOpenBotAvatarApi::class)`; without it, the compiler warns.

```kotlin
// The body stays: calm as in idle, leaning, with narrowed eyes.
val listening = AvatarStateDefinition("listening") { ctx ->
    ctx.rest().copy(roll = 6 + ctx.wave(4.0), squintL = 0.3, squintR = 0.3)
}

BotAvatar(definition, Modifier.size(96.dp), state = listening)
```

A definition works wherever a state does: `BotAvatar`, `FrameOptions`, `PoseInput`, `MoodState`, `AvatarAnimator`, and `AvatarPlayer`.
Changes to and from it ease like any other, and a mood shapes its eyes.

- `id` names the state. Two definitions with the same `id` are the same state, so you can build one on every composition without restarting it. The newer definition is the one shown. A definition with the `id` of a built-in state, such as `"idle"`, replaces it without easing.
- `pose` receives the clock (`t`, `wave`), the state's age (`stateAge`), the mood, `reducedMotion`, the resting pose (`base`), and the calm idle pose (`rest`). It must be a pure function of the context. Under `reducedMotion`, `t` is 0 and `wave` returns 0.
- `startAge` is the age in seconds a state has when an avatar mounts in it. The default is 0.
- A pose number that isn't finite keeps the base pose's. A `pose` function that throws isn't caught.
- `stateDefinition(AvatarState.BLOCKED)` returns the definition behind a name, to build a variant on.

### Marks

A state can make the body give way to a mark, as `THINKING` and `BLOCKED` do. A mark is an `id` and the outlines it's made of, placed from the mark's center.
This mark is a question mark: a hook over a dot, sized in `unit`:

```kotlin
val question = AvatarMark("question") { ctx ->
    val u = ctx.unit
    fun arc(r: Double, large: Int, sweep: Int, x: Double, y: Double) =
        "A${r * u} ${r * u} 0 $large $sweep ${x * u} ${y * u}"
    val hook = "M${-0.26 * u} ${-0.062 * u}" +
        arc(0.26, 1, 1, 0.13, 0.163) + arc(0.08, 0, 0, 0.09, 0.232) +
        arc(0.09, 0, 1, -0.09, 0.232) + arc(0.26, 0, 1, 0.04, 0.007) +
        arc(0.08, 1, 0, -0.08, -0.062) + arc(0.09, 0, 1, -0.26, -0.062) + "Z"
    val w = 0.52 * u
    val h = 0.645 * u
    val r = 0.115 * u
    val dot = "M$r 0A$r $r 0 1 1 ${-r} 0A$r $r 0 1 1 $r 0Z"
    val top = -(h + 0.1 * u + 2 * r) / 2
    // The mark tilts from side to side. `time` is 0 under reduced motion, so it holds still.
    val turn = 0.12 * sin(2.4 * ctx.time)
    listOf(
        MarkPart("question-hook", hook, PathBox(-w / 2, -h / 2, w, h), y = top + h / 2, turn = turn),
        // A circle that fills its box: a material draws it from a ready ball.
        MarkPart("question-dot", dot, PathBox(-r, -r, 2 * r, 2 * r), y = -top - r, turn = turn, round = true),
    )
}

// The body gives way to the mark: the bot needs an answer.
val asking = AvatarStateDefinition("asking") { ctx ->
    ctx.base.copy(marks = listOf(PoseMark(question, 1.0)))
}
```

Keep each outline the same from frame to frame, and animate a part with `x`, `y`, `turn`, and `scale`: a live renderer keeps what it builds per outline.
A part without an outline or a `box` with a size is left out. An `x`, `y`, `turn`, or `scale` that isn't finite counts as 0, 0, 0, and 1.

## Materials

A material is the body's finish: one function that renders a shape to an image. The painter asks it for the body and for each part of a mark, places the images, and draws the ribbons itself.
Where the function returns null, the painter falls back to the flat paths and eyes. This material shades the balls and bars of marks, and leaves bodies flat:

```kotlin
val shaded = AvatarMaterial("shaded") { shape, _, look, scale ->
    if (shape !is MaterialShape.Capsule) return@AvatarMaterial null
    val b = shape.bounds
    val half = b.maxY
    val size = ceil(2 * half * scale).toInt()
    val image = ImageBitmap(size, size)
    val px = (size / (2 * half)).toFloat()
    val paint = Paint().apply {
        shader = LinearGradientShader(Offset.Zero, Offset(0f, size.toFloat()), listOf(Color.White, look.color))
    }
    val left = (b.minX + half).toFloat() * px
    val right = (b.maxX + half).toFloat() * px
    val radius = shape.radius.toFloat() * px
    Canvas(image).drawRoundRect(left, 0f, right, size.toFloat(), radius, radius, paint)
    MaterialImage(image, cx = 0.0, cy = 0.0, radius = half)
}

BotAvatar(definition, Modifier.size(96.dp), state = AvatarState.THINKING, material = shaded)
```

- A body is a `MaterialShape.Volume`, with the eyes to set into it. A `round` part of a mark is a `MaterialShape.Capsule`; any other part is a `MaterialShape.Volume` built from its outline. Marks have no eyes.
- `render` runs while drawing: on every frame for the body, and once per mark, color, and size. Equal shapes are the same model, so keep what you build by its shape.
- Don't change an image after you return it.
- An avatar at `depth` 0 has no surface, so its body stays flat.
- `margin` states how far the body's image reaches outside its projected bounds. A host that clips must reserve this much room.

`DrawScope.drawAvatarFrame` takes the same `material`.

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
See the [avatar definition contract](https://github.com/DominikScholz/open-bot-avatar/blob/396073383830bf7d1c6b83316a9ff2111192df03/docs/avatar-contract.md):

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
- Handle pill eyes, stroked arcs, and filled eye paths. Marks use ordinary slices; the paths of round parts are flagged `round`.
- A mark's `d` is the host's own text. Escape it before you write it into markup, as `toSvg` does.
- To draw the body in 3D, use `surface`: the view, the body's scale, both eyes, and the volume.
- Apply the supplied matrices to keep ribbons and marks independent of the body's root motion.

In Compose, `DrawScope.drawAvatarFrame` paints the frame. Pass the same body color to it and `FrameOptions.color`, because ribbon palettes are generated before painting.
`BotAvatar` handles this automatically, including live theme changes.

Use `AvatarPlayer` for your animation host, or `AvatarAnimator` and `computePose` for finer control.
Keep one player per on-screen avatar to preserve state age through mood changes and definition replacements.

## Differences from the TypeScript package

- `AvatarState` is an enum whose values are themselves state definitions; `AvatarMood` and the legacy `AvatarMotion` use enum values instead of strings.
- `renderAvatarScene`, `sceneFrame`, and `renderer: "scene"` are left out because they target CSS 3D.
- `avatarExtent` and bitmap fitting are left out because Compose draws without an intermediate bitmap. A material's `margin` is therefore information for hosts that clip.
- The material extension point exists, but no built-in materials ship yet: `fluffy` and `glass` are left out.
- A custom state's `pose` returns a whole `Pose` built from `ctx.base` or `ctx.rest()`, where upstream takes the fields that differ.
- A material's image must not change once returned, where upstream copies it at once.
- A frame's `surface` carries the volume, so the painter needs only the frame. Slices don't carry their polygons.
- An outline of a mark that isn't path data draws nothing in Compose, where a browser draws the part it can parse.
- The advisory `validate.sh --strict` checks (`faceRoom`, `silhouetteSeams`) are left out. They aren't part of the upstream public API.

## Build and test

Build and run the tests without an emulator:

```sh
./gradlew build
```

- `ParityTest` compares validation, volumes, eye outlines, frames, poses, state clocks, custom states, marks, and SVG output with versioned TypeScript fixtures.
- `FastPathsTest` compares the fast geometry with its plain definitions, bit for bit.
- `AvatarPlayerTest` checks legacy calls, transitions, replacement marks, ribbon stacking, colors, and reduced motion.
- `CustomStateTest` checks custom states and marks beyond the fixtures.
- `BotAvatarRenderTest` and `MaterialTest` use Skia and Robolectric; renders are saved in `compose/build/renders`.

To update upstream data, regenerate `AvatarCatalogData.kt` and fixtures with Node.js 22.18 or later.
Replace `UPSTREAM_PATH` with the path to a clean checkout of the linked upstream revision:

```sh
node scripts/sync-upstream.ts UPSTREAM_PATH
./gradlew build
```

Fixtures record the upstream version and Git revision and cover every state and mood, custom states and marks, legacy options, interrupted transitions, volumes, and ribbon gradients.

## License and attribution

[MIT](LICENSE). Port of [Open Bot Avatar](https://github.com/DominikScholz/open-bot-avatar) by Dominik Scholz.
