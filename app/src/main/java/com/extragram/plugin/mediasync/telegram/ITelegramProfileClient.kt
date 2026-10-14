package com.extragram.plugin.mediasync.telegram

/**
 * Interface defining Telegram profile management operations.
 */
interface ITelegramProfileClient {

    /**
     * Retrieves the user's current profile bio/about text from Telegram.
     */
    suspend fun getCurrentBio(): String?

    /**
     * Updates the user's profile bio/about text using MTProto account.updateProfile.
     *
     * @param newBio The new bio text (max 70 characters).
     * @throws FloodWaitException if Telegram enforces a cooldown period.
     * @throws TelegramSearchException on RPC or network failures.
     */
    suspend fun updateBio(newBio: String): Result<Unit>
}
