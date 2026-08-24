package org.godotengine.plugin.android.godot_exoplayer

/** Opt-in regular-expression filter for media requests exposed to Godot. */
internal class ObservedUrlPattern private constructor(private val regex: Regex) {
    fun matches(url: String): Boolean = regex.containsMatchIn(url)

    internal companion object {
        fun from(value: String): ObservedUrlPattern {
            require(value.isNotBlank()) { "observedUrlPattern must not be blank" }
            return try {
                ObservedUrlPattern(Regex(value))
            } catch (error: Exception) {
                throw IllegalArgumentException("observedUrlPattern is not a valid regular expression", error)
            }
        }
    }
}
