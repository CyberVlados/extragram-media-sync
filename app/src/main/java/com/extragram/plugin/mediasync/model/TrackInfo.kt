package com.extragram.plugin.mediasync.model

/**
 * Represents the current playback information extracted from an active MediaController.
 *
 * @property title The track title.
 * @property artist The track artist or album artist.
 * @property durationSeconds Track duration converted to seconds (0 if unknown/live stream).
 * @property isPlaying Whether the track is currently actively playing.
 * @property packageName The package name of the media player application (e.g. com.spotify.music).
 */
data class TrackInfo(
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val isPlaying: Boolean,
    val packageName: String? = null
) {
    /**
     * Unique identifier representing the current song identity.
     */
    val trackKey: String
        get() = "${artist.trim()} - ${title.trim()}".lowercase()

    override fun toString(): String {
        val state = if (isPlaying) "PLAYING" else "PAUSED/STOPPED"
        return "[$state] $artist - $title (${durationSeconds}s) [App: ${packageName ?: "unknown"}]"
    }
}
