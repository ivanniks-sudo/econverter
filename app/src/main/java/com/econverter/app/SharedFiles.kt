package com.econverter.app

import android.content.Intent
import android.net.Uri

// EXTRA_STREAM is the share contract; ClipData is used by some file managers instead.
@Suppress("DEPRECATION")
internal fun sharedFileUris(intent: Intent): List<Uri> {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
    val streams = try {
        // Inspect the actual type: malformed extras must not prevent the ClipData fallback.
        val extra = intent.extras?.get(Intent.EXTRA_STREAM)
        when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(extra as? Uri)
            else -> (extra as? ArrayList<*>)?.filterIsInstance<Uri>().orEmpty()
        }
    } catch (_: RuntimeException) {
        emptyList()
    }
    if (streams.isNotEmpty()) return streams.distinct()
    val clip = intent.clipData ?: return emptyList()
    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct()
    return if (intent.action == Intent.ACTION_SEND) uris.take(1) else uris
}
