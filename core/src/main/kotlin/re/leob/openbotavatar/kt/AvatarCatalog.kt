package re.leob.openbotavatar.kt

/** Definitions that ship with the library; scripts/sync-upstream.ts refreshes the data. */
public object AvatarCatalog {
    /** Starters, the same set as the npm package's `examples`. */
    public val examples: List<AvatarDefinition> get() = generatedExamples

    /** The community gallery, by name. */
    public val gallery: List<AvatarDefinition> get() = generatedGallery

    public val all: List<AvatarDefinition> by lazy { examples + gallery }

    public fun definition(id: String): AvatarDefinition? = all.firstOrNull { it.id == id }

    /** The hex color a catalog avatar is shown in until someone picks another. */
    public fun color(id: String): String = generatedColors[id] ?: DEFAULT_COLOR
}
