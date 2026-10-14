package com.extragram.plugin.mediasync.telegram

import com.extragram.plugin.mediasync.model.TelegramAudioDocument

/**
 * Interface defining Telegram MTProto search operations.
 */
interface ITelegramSearchClient {

    /**
     * Executes MTProto messages.searchGlobal query with inputMessagesFilterMusic.
     *
     * @param query The search query string: "Artist - Title"
     * @param limit Maximum results to fetch (default: 20)
     * @return List of retrieved Telegram audio documents
     * @throws FloodWaitException if Telegram enforces a cooldown period
     * @throws TelegramSearchException on other RPC or network failures
     */
    suspend fun searchGlobalMusic(query: String, limit: Int = 20): List<TelegramAudioDocument>
}
