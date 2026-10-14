package com.extragram.plugin.mediasync.telegram

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Implementation of ITelegramProfileClient that updates the user's bio/status
 * via Extragram / Telegram MTProto account.updateProfile.
 */
class ExtragramBridgeProfileClient : ITelegramProfileClient {

    companion object {
        private const val TAG = "ExtragramProfileClient"

        private const val CLASS_CONNECTIONS_MANAGER = "org.telegram.tgnet.ConnectionsManager"
        private const val CLASS_MESSAGES_CONTROLLER = "org.telegram.messenger.MessagesController"
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

    // Simulated bio in standalone mode for local testing
    private var simulatedBio: String = ""

    override suspend fun getCurrentBio(): String? = withContext(Dispatchers.IO) {
        if (!isExtragramRuntimeAvailable) {
            Log.d(TAG, "[STANDALONE] Returning simulated current bio: \"$simulatedBio\"")
            return@withContext simulatedBio
        }

        try {
            val userConfigClass = Class.forName(CLASS_USER_CONFIG)
            val selectedAccount = userConfigClass.getField("selectedAccount").getInt(null)

            val getInstanceMethod = userConfigClass.getMethod("getInstance", Int::class.javaPrimitiveType)
            val userConfigInstance = getInstanceMethod.invoke(null, selectedAccount)
            val clientUserId = userConfigClass.getMethod("getClientUserId").invoke(userConfigInstance) as? Number ?: 0L

            val msgCtrlClass = Class.forName(CLASS_MESSAGES_CONTROLLER)
            val getMsgCtrlMethod = msgCtrlClass.getMethod("getInstance", Int::class.javaPrimitiveType)
            val msgController = getMsgCtrlMethod.invoke(null, selectedAccount)

            val getUserFullMethod = msgCtrlClass.getMethod("getUserFull", Long::class.javaPrimitiveType)
            val userFull = getUserFullMethod.invoke(msgController, clientUserId.toLong())

            if (userFull != null) {
                val about = userFull.javaClass.getField("about").get(userFull)?.toString()
                Log.d(TAG, "Retrieved current bio from MessagesController: \"$about\"")
                return@withContext about
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to retrieve current bio via reflection: ${t.message}")
        }

        null
    }

    override suspend fun updateBio(newBio: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Requesting profile bio update: \"$newBio\" (${newBio.length} chars)")

        if (!isExtragramRuntimeAvailable) {
            return@withContext updateBioStandalone(newBio)
        }

        try {
            updateBioViaMtProto(newBio)
            Result.success(Unit)
        } catch (flood: FloodWaitException) {
            Log.w(TAG, "[FLOOD_WAIT] Profile update rate limit hit: wait ${flood.waitSeconds}s")
            Result.failure(flood)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to update profile bio via MTProto: ${t.message}", t)
            Result.failure(t)
        }
    }

    /**
     * Executes MTProto account.updateProfile with flags |= 4 and about = newBio.
     */
    private suspend fun updateBioViaMtProto(newBio: String): Unit =
        suspendCancellableCoroutine { continuation ->
            try {
                // 1. Get current account
                val userConfigClass = Class.forName(CLASS_USER_CONFIG)
                val selectedAccount = userConfigClass.getField("selectedAccount").getInt(null)

                // 2. Instantiate TL_account_updateProfile
                val updateProfileClass = Class.forName("org.telegram.tgnet.TLRPC\$TL_account_updateProfile")
                val req = updateProfileClass.getDeclaredConstructor().newInstance()

                // Flag 4 indicates that 'about' (bio) is being modified
                updateProfileClass.getField("flags").set(req, 4)
                updateProfileClass.getField("about").set(req, newBio)

                // 3. Obtain ConnectionsManager
                val connMgrClass = Class.forName(CLASS_CONNECTIONS_MANAGER)
                val getConnInstance: Method = connMgrClass.getMethod("getInstance", Int::class.javaPrimitiveType)
                val connManager = getConnInstance.invoke(null, selectedAccount)

                // 4. Create RequestDelegate proxy
                val requestDelegateClass = Class.forName("org.telegram.tgnet.RequestDelegate")
                val proxyDelegate = Proxy.newProxyInstance(
                    requestDelegateClass.classLoader,
                    arrayOf(requestDelegateClass),
                    InvocationHandler { _, method, args ->
                        if (method?.name == "run") {
                            val response = args?.getOrNull(0)
                            val error = args?.getOrNull(1)

                            if (error != null) {
                                val errorText = try {
                                    error.javaClass.getField("text").get(error)?.toString() ?: ""
                                } catch (e: Throwable) { "" }

                                Log.w(TAG, "MTProto account.updateProfile error: $errorText")
                                if (errorText.startsWith("FLOOD_WAIT_")) {
                                    val waitSec = errorText.removePrefix("FLOOD_WAIT_").trim().toIntOrNull() ?: 15
                                    continuation.resumeWithException(FloodWaitException(waitSec))
                                } else {
                                    continuation.resumeWithException(TelegramNetworkException("Profile update error: $errorText"))
                                }
                            } else {
                                Log.i(TAG, "MTProto account.updateProfile succeeded! Bio updated to: \"$newBio\"")
                                try {
                                    // Update local user in MessagesController if response is TLRPC.User
                                    if (response != null) {
                                        val msgCtrlClass = Class.forName(CLASS_MESSAGES_CONTROLLER)
                                        val getMsgCtrlMethod = msgCtrlClass.getMethod("getInstance", Int::class.javaPrimitiveType)
                                        val msgController = getMsgCtrlMethod.invoke(null, selectedAccount)
                                        val putUserMethod = msgCtrlClass.getMethod(
                                            "putUser",
                                            Class.forName("org.telegram.tgnet.TLRPC\$User"),
                                            Boolean::class.javaPrimitiveType
                                        )
                                        putUserMethod.invoke(msgController, response, false)
                                    }
                                } catch (ignored: Throwable) {}

                                continuation.resume(Unit)
                            }
                        }
                        null
                    }
                )

                // 5. Send request
                val sendRequestMethod = connMgrClass.getMethod(
                    "sendRequest",
                    Class.forName("org.telegram.tgnet.TLObject"),
                    requestDelegateClass
                )
                sendRequestMethod.invoke(connManager, req, proxyDelegate)

            } catch (t: Throwable) {
                continuation.resumeWithException(t)
            }
        }

    /**
     * Standalone/demo implementation for local testing without Extragram client.
     */
    private suspend fun updateBioStandalone(newBio: String): Result<Unit> {
        delay(300)
        simulatedBio = newBio
        Log.i(TAG, "[STANDALONE SUCCESS] Profile bio updated in demo mode to: \"$newBio\"")
        return Result.success(Unit)
    }
}
