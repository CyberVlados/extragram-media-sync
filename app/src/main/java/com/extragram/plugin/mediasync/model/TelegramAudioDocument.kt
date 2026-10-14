package com.extragram.plugin.mediasync.model

import android.util.Base64
import org.json.JSONObject

/**
 * Represents a matched audio document returned from Telegram's MTProto messages.searchGlobal query.
 *
 * @property id The Telegram document ID.
 * @property accessHash The document access hash required for MTProto operations.
 * @property fileReference Optional binary file reference bytes.
 * @property title The audio track title.
 * @property performer The audio track artist/performer.
 * @property durationSeconds Duration in seconds according to Telegram's DocumentAttributeAudio.
 * @property mimeType The MIME type of the audio file (e.g., "audio/mpeg").
 * @property size The file size in bytes.
 */
data class TelegramAudioDocument(
    val id: Long,
    val accessHash: Long,
    val fileReference: ByteArray? = null,
    val title: String,
    val performer: String,
    val durationSeconds: Int,
    val mimeType: String = "audio/mpeg",
    val size: Long = 0L
) {
    /**
     * Unique string identifier for this Telegram audio file (e.g. for profile music updates).
     */
    val fileIdFormatted: String
        get() = "doc_${id}_${accessHash}"

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("accessHash", accessHash)
            put("title", title)
            put("performer", performer)
            put("durationSeconds", durationSeconds)
            put("mimeType", mimeType)
            put("size", size)
            if (fileReference != null && fileReference.isNotEmpty()) {
                put("fileReference", Base64.encodeToString(fileReference, Base64.NO_WRAP))
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TelegramAudioDocument

        if (id != other.id) return false
        if (accessHash != other.accessHash) return false
        if (fileReference != null) {
            if (other.fileReference == null) return false
            if (!fileReference.contentEquals(other.fileReference)) return false
        } else if (other.fileReference != null) return false
        if (title != other.title) return false
        if (performer != other.performer) return false
        if (durationSeconds != other.durationSeconds) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + accessHash.hashCode()
        result = 31 * result + (fileReference?.contentHashCode() ?: 0)
        result = 31 * result + title.hashCode()
        result = 31 * result + performer.hashCode()
        result = 31 * result + durationSeconds
        return result
    }

    override fun toString(): String {
        return "TelegramAudioDocument(id=$id, title='$title', performer='$performer', duration=${durationSeconds}s, size=$size)"
    }

    companion object {
        fun fromJson(json: JSONObject): TelegramAudioDocument {
            val fileRefStr = json.optString("fileReference", null)
            val fileRefBytes = if (!fileRefStr.isNullOrEmpty()) {
                try {
                    Base64.decode(fileRefStr, Base64.NO_WRAP)
                } catch (ignored: Throwable) {
                    null
                }
            } else null

            return TelegramAudioDocument(
                id = json.getLong("id"),
                accessHash = json.optLong("accessHash", 0L),
                fileReference = fileRefBytes,
                title = json.optString("title", ""),
                performer = json.optString("performer", ""),
                durationSeconds = json.optInt("durationSeconds", 0),
                mimeType = json.optString("mimeType", "audio/mpeg"),
                size = json.optLong("size", 0L)
            )
        }
    }
}
