package com.extragram.plugin.mediasync.telegram

import com.extragram.plugin.mediasync.model.TelegramAudioDocument

/**
 * Interface for controlling Extragram / Telegram Profile Audio Widget.
 */
interface ITelegramProfileAudioClient {

    /**
     * Checks if Extragram runtime is available and an active user account is signed in.
     */
    fun isSessionAvailable(): Boolean

    /**
     * Returns a human-readable description of the active account session (e.g. "Account #1 (User ID: 123456)").
     */
    fun getActiveAccountLabel(): String

    /**
     * Sets the specified audio document as the user's active Profile Audio Widget.
     *
     * @param document The matched Telegram audio document to display on profile.
     * @throws FloodWaitException if rate-limited by Telegram servers.
     * @throws TelegramSearchException on RPC or connection error.
     */
    suspend fun setProfileAudio(document: TelegramAudioDocument): Result<Unit>

    /**
     * Clears or removes the current Profile Audio Widget when music stops.
     */
    suspend fun clearProfileAudio(): Result<Unit>

    /**
     * Retrieves the currently set profile audio document, if any.
     */
    suspend fun getCurrentProfileAudio(): TelegramAudioDocument?
}
