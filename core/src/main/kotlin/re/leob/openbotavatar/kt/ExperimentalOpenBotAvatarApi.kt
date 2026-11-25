package re.leob.openbotavatar.kt

/**
 * Marks what is written against the renderer's own model of a pose or a body: custom states, marks
 * and materials. Its parts may be renamed or removed in a minor release while the API settles. The
 * six names of [AvatarState] are stable.
 */
@RequiresOptIn(
    message = "Custom states, marks and materials are experimental and may change in a minor release.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExperimentalOpenBotAvatarApi
