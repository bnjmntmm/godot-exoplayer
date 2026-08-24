package org.godotengine.plugin.android.godot_exoplayer

/** Keeps credential-bearing query strings out of diagnostic output. */
internal object MediaUrlLog {
    fun redact(url: String): String = url.substringBefore('?')
}
