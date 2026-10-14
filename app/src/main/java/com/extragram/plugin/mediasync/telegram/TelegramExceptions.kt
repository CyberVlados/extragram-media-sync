package com.extragram.plugin.mediasync.telegram

/**
 * Base exception for Telegram search and MTProto operations.
 */
open class TelegramSearchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when Telegram API returns FLOOD_WAIT_X.
 *
 * @property waitSeconds The number of seconds Telegram requires the client to wait before sending more requests.
 */
class FloodWaitException(
    val waitSeconds: Int,
    message: String = "Telegram API FLOOD_WAIT: must wait $waitSeconds seconds before retrying."
) : TelegramSearchException(message)

/**
 * Thrown when a network issue or RPC failure occurs while querying Telegram servers.
 */
class TelegramNetworkException(
    message: String,
    cause: Throwable? = null
) : TelegramSearchException(message, cause)

/**
 * Thrown when neither Extragram nor Telegram client instances can be accessed.
 */
class TelegramClientNotFoundException(
    message: String = "Telegram / Extragram client instance not found in current environment."
) : TelegramSearchException(message)
