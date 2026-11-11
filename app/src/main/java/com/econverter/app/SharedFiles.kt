package com.econverter.app

import android.content.Intent
import android.net.Uri
import android.os.Parcelable

// EXTRA_STREAM is the share contract; ClipData is used by some file managers instead.
@Suppress("DEPRECATION")
internal fun sharedFileUris(intent: Intent): List<Uri> {
    val streams = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull((intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM).orEmpty().filterIsInstance<Uri>()
        else -> return emptyList()
    }
    if (streams.isNotEmpty()) return streams.distinct()
    val clip = intent.clipData ?: return emptyList()
    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct()
    return if (intent.action == Intent.ACTION_SEND) uris.take(1) else uris
}
