package com.extragram.plugin.mediasync.telegram

import android.util.Log
import com.extragram.plugin.mediasync.model.TelegramAudioDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCancellableCoroutine

/**
 * Implementation of ITelegramSearchClient that interfaces with Extragram / Telegram MTProto engine.
 *
 * It dynamically connects to Telegram's ConnectionsManager / AccountInstance when running
 * inside Extragram, and provides a safe fallback when running standalone during development.
 */
class ExtragramBridgeSearchClient : ITelegramSearchClient {

    companion object {
        private const val TAG = "ExtragramBridgeSearch"

        // Telegram / Extragram core classes
        private const val CLASS_CONNECTIONS_MANAGER = "org.telegram.tgnet.ConnectionsManager"
        private const val CLASS_ACCOUNT_INSTANCE = "org.telegram.messenger.AccountInstance"
        private const val CLASS_USER_CONFIG = "org.telegram.messenger.UserConfig"
    }

    private val isExtragramRuntimeAvailable: Boolean by lazy {
        try {
            Class.forName(CLASS_CONNECTIONS_MANAGER)
            true
        } catch (ignored: ClassNotFoundException) {
            false
        }
    }

    override suspend fun searchGlobalMusic(query: String, limit: Int): List<TelegramAudioDocument> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Initiating Telegram searchGlobalMusic query='$query', limit=$limit")

            if (isExtragramRuntimeAvailable) {
                searchViaExtragramMtProto(query, limit)
            } else {
                searchViaStandaloneFallback(query, limit)
            }
        }

    /**
     * Executes MTProto messages.searchGlobal with TL_inputMessagesFilterMusic via reflection.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun searchViaExtragramMtProto(query: String, limit: Int): List<TelegramAudioDocument> =
        suspendCancellableCoroutine { continuation ->
            try {
                // 1. Get current account
                val userConfigClass = Class.forName(CLASS_USER_CONFIG)
                val selectedAccountField = userConfigClass.getField("selectedAccount")
                val currentAccount = selectedAccountField.getInt(null)

                // 2. Instantiate TL_messages_searchGlobal
                val searchGlobalClass = Class.forName("org.telegram.tgnet.TLRPC\$TL_messages_searchGlobal")
                val searchReq = searchGlobalClass.getDeclaredConstructor().newInstance()

                // Set query and music filter
                searchGlobalClass.getField("q").set(searchReq, query)
                searchGlobalClass.getField("limit").set(searchReq, limit)

                val filterMusicClass = Class.forName("org.telegram.tgnet.TLRPC\$TL_inputMessagesFilterMusic")
                val filterInstance = filterMusicClass.getDeclaredConstructor().newInstance()
                searchGlobalClass.getField("filter").set(searchReq, filterInstance)

                // Set empty peer
                val peerEmptyClass = Class.forName("org.telegram.tgnet.TLRPC\$TL_inputPeerEmpty")
                val peerEmptyInstance = peerEmptyClass.getDeclaredConstructor().newInstance()
                searchGlobalClass.getField("offset_peer").set(searchReq, peerEmptyInstance)

                // 3. Get ConnectionsManager.getInstance(currentAccount)
                val connMgrClass = Class.forName(CLASS_CONNECTIONS_MANAGER)
                val getInstanceMethod: Method = connMgrClass.getMethod("getInstance", Int::class.javaPrimitiveType)
                val connManager = getInstanceMethod.invoke(null, currentAccount)

                // 4. Send request via sendRequest(TLObject, RequestDelegate)
                val requestDelegateClass = Class.forName("org.telegram.tgnet.RequestDelegate")
                val proxyDelegate = java.lang.reflect.Proxy.newProxyInstance(
                    requestDelegateClass.classLoader,
                    arrayOf(requestDelegateClass)
                ) { _, method, args ->
                    if (method.name == "run") {
                        val response = args?.getOrNull(0)
                        val error = args?.getOrNull(1)

                        if (error != null) {
                            val errorText = error.javaClass.getField("text").get(error)?.toString() ?: ""
                            Log.w(TAG, "MTProto Error received: $errorText")
                            if (errorText.startsWith("FLOOD_WAIT_")) {
                                val waitSec = errorText.removePrefix("FLOOD_WAIT_").trim().toIntOrNull() ?: 10
                                continuation.resumeWithException(FloodWaitException(waitSec))
                            } else {
                                continuation.resumeWithException(TelegramNetworkException("MTProto error: $errorText"))
                            }
                        } else if (response != null) {
                            try {
                                val documents = parseMessagesResponse(response)
                                continuation.resume(documents)
                            } catch (e: Throwable) {
                                continuation.resumeWithException(TelegramSearchException("Failed to parse response", e))
                            }
                        } else {
                            continuation.resume(emptyList())
                        }
                    }
                    null
                }

                val sendRequestMethod = connMgrClass.getMethod(
                    "sendRequest",
                    Class.forName("org.telegram.tgnet.TLObject"),
                    requestDelegateClass
                )
                sendRequestMethod.invoke(connManager, searchReq, proxyDelegate)

            } catch (t: Throwable) {
                Log.e(TAG, "Failed to invoke MTProto via reflection", t)
                continuation.resumeWithException(TelegramSearchException("Reflection error invoking MTProto", t))
            }
        }

    /**
     * Extracts TelegramAudioDocuments from a TLRPC.messages_Messages response.
     */
    private fun parseMessagesResponse(response: Any): List<TelegramAudioDocument> {
        val results = mutableListOf<TelegramAudioDocument>()
        try {
            val messagesField = response.javaClass.getField("messages")
            val messagesList = messagesField.get(response) as? List<*> ?: return emptyList()

            for (msg in messagesList) {
                if (msg == null) continue
                val media = try { msg.javaClass.getField("media").get(msg) } catch (e: Throwable) { null } ?: continue
                val document = try { media.javaClass.getField("document").get(media) } catch (e: Throwable) { null } ?: continue

                val docId = document.javaClass.getField("id").getLong(document)
                val accessHash = document.javaClass.getField("access_hash").getLong(document)
                val size = document.javaClass.getField("size").getLong(document)
                val mimeType = document.javaClass.getField("mime_type").get(document)?.toString() ?: "audio/mpeg"

                val fileRef = try {
                    document.javaClass.getField("file_reference").get(document) as? ByteArray
                } catch (ignored: Throwable) { null }

                val attributes = document.javaClass.getField("attributes").get(document) as? List<*> ?: emptyList<Any>()

                var title = ""
                var performer = ""
                var duration = 0

                for (attr in attributes) {
                    if (attr != null && attr.javaClass.name.contains("TL_documentAttributeAudio")) {
                        duration = attr.javaClass.getField("duration").getInt(attr)
                        title = attr.javaClass.getField("title").get(attr)?.toString() ?: ""
                        performer = attr.javaClass.getField("performer").get(attr)?.toString() ?: ""
                        break
                    }
                }

                if (docId > 0 && duration > 0) {
                    results.add(
                        TelegramAudioDocument(
                            id = docId,
                            accessHash = accessHash,
                            fileReference = fileRef,
                            title = title,
                            performer = performer,
                            durationSeconds = duration,
                            mimeType = mimeType,
                            size = size
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error parsing TLRPC messages response", t)
        }
        return results
    }

    /**
     * Development / Standalone test fallback when the APK is run outside Extragram client.
     * Simulates MTProto response for testing the sync pipeline locally.
     */
    private suspend fun searchViaStandaloneFallback(query: String, limit: Int): List<TelegramAudioDocument> {
        Log.i(TAG, "Extragram client not found in runtime. Operating in Standalone/Demo mode for query='$query'")
        // Simulate network latency
        delay(400)

        // Generate candidate documents for testing duration matching
        val parts = query.split("-", limit = 2)
        val artist = parts.getOrNull(0)?.trim() ?: "Artist"
        val title = parts.getOrNull(1)?.trim() ?: query.trim()

        return listOf(
            TelegramAudioDocument(
                id = 5829104829103L,
                accessHash = 92019481029481L,
                fileReference = byteArrayOf(0x01, 0x02, 0x03),
                title = title,
                performer = artist,
                durationSeconds = 200,
                mimeType = "audio/mpeg",
                size = 7850000L
            ),
            TelegramAudioDocument(
                id = 5829104829104L,
                accessHash = 92019481029482L,
                fileReference = byteArrayOf(0x04, 0x05, 0x06),
                title = "$title (Remix)",
                performer = artist,
                durationSeconds = 245,
                mimeType = "audio/mpeg",
                size = 9120000L
            )
        )
    }
}
