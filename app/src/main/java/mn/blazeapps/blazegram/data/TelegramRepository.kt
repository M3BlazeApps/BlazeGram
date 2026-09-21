package mn.blazeapps.blazegram.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import mn.blazeapps.blazegram.data.model.AuthState
import mn.blazeapps.blazegram.data.model.ChatSummary
import mn.blazeapps.blazegram.data.model.ChatType
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TelegramRepository private constructor(private val context: Context) : Client.ResultHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = UserPreferences(context)

    private var client: Client? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initializing)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val chatMap = ConcurrentHashMap<Long, TdApi.Chat>()
    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats: StateFlow<List<ChatSummary>> = _chats.asStateFlow()

    private val _isLoadingChats = MutableStateFlow(false)
    val isLoadingChats: StateFlow<Boolean> = _isLoadingChats.asStateFlow()

    private val _selectedChatId = MutableStateFlow<Long?>(null)
    val selectedChatId: StateFlow<Long?> = _selectedChatId.asStateFlow()

    // Async media send trackers
    private val pendingMediaSends = ConcurrentHashMap<Long, PendingMediaSend>()
    private val fileIdToTempMessageId = ConcurrentHashMap<Int, Long>()
    private val recentlyCompletedSends = ConcurrentHashMap<Long, TdApi.Message>()
    private val recentlyFailedSends = ConcurrentHashMap<Long, TdApi.Error>()

    private class PendingMediaSend(
        val tempMessageId: Long,
        val fileId: Int?,
        val expectedSize: Long,
        val onProgress: ((Float) -> Unit)?,
        val continuation: kotlinx.coroutines.CancellableContinuation<TdApi.Message>
    )

    init {
        try {
            System.loadLibrary("tdjni")
        } catch (t: Throwable) {
            Log.w(TAG, "System.loadLibrary(tdjni) caught: ${t.message}")
        }
        createClient()
    }

    private fun createClient() {
        if (client != null) return
        client = Client.create(this, null, null)
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(2))
        } catch (_: Throwable) {}
    }

    override fun onResult(obj: TdApi.Object?) {
        if (obj == null) return
        when (obj.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateAuthorizationState
                handleAuthorizationState(update.authorizationState)
            }
            TdApi.UpdateNewChat.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateNewChat
                chatMap[update.chat.id] = update.chat
                refreshChatList()
            }
            TdApi.UpdateChatTitle.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateChatTitle
                chatMap[update.chatId]?.let {
                    it.title = update.title
                    refreshChatList()
                }
            }
            TdApi.UpdateChatPosition.CONSTRUCTOR -> {
                refreshChatList()
            }
            TdApi.UpdateFile.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateFile
                handleFileUpdate(update.file)
            }
            TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateMessageSendSucceeded
                handleMessageSendSucceeded(update.message, update.oldMessageId)
            }
            TdApi.UpdateMessageSendFailed.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateMessageSendFailed
                handleMessageSendFailed(update.message, update.oldMessageId, update.error)
            }
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        Log.d(TAG, "AuthorizationState: ${state.javaClass.simpleName}")
        when (state.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                _authState.value = AuthState.NeedParameters
                if (prefs.hasApiCredentials()) {
                    applyTdlibParameters(prefs.apiId, prefs.apiHash)
                }
            }
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                _authState.value = AuthState.NeedPhoneNumber
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                _authState.value = AuthState.NeedCode
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                _authState.value = AuthState.NeedPassword
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                _authState.value = AuthState.Ready()
                fetchCurrentUser()
                loadAllChats()
            }
            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> {
                _authState.value = AuthState.LoggingOut
                _isLoadingChats.value = false
            }
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                _authState.value = AuthState.Closed
                _isLoadingChats.value = false
                chatMap.clear()
                _chats.value = emptyList()
                client = null
                createClient()
            }
            else -> {
                Log.d(TAG, "Unhandled auth state: ${state.constructor}")
            }
        }
    }

    fun applyTdlibParameters(apiId: Int, apiHash: String) {
        prefs.apiId = apiId
        prefs.apiHash = apiHash

        val dbDir = File(context.filesDir, "tdlib")
        if (!dbDir.exists()) dbDir.mkdirs()

        val filesDir = File(context.filesDir, "tdlib_files")
        if (!filesDir.exists()) filesDir.mkdirs()

        val request = TdApi.SetTdlibParameters().apply {
            this.useTestDc = false
            this.databaseDirectory = dbDir.absolutePath
            this.filesDirectory = filesDir.absolutePath
            this.databaseEncryptionKey = ByteArray(0)
            this.useFileDatabase = true
            this.useChatInfoDatabase = true
            this.useMessageDatabase = true
            this.useSecretChats = false
            this.apiId = apiId
            this.apiHash = apiHash
            this.systemLanguageCode = "en"
            this.deviceModel = "Android"
            this.systemVersion = android.os.Build.VERSION.RELEASE ?: "Unknown"
            this.applicationVersion = "1.0"
        }

        client?.send(request) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "SetTdlibParameters error: ${result.message}")
                _authState.value = AuthState.Error(result.message)
            }
        }
    }

    fun setPhoneNumber(phoneNumber: String) {
        prefs.phoneNumber = phoneNumber
        val settings = TdApi.PhoneNumberAuthenticationSettings()
        client?.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings)) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "SetAuthenticationPhoneNumber error: ${result.message}")
                _authState.value = AuthState.Error(result.message)
            }
        }
    }

    fun sendCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code)) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "CheckAuthenticationCode error: ${result.message}")
                _authState.value = AuthState.Error(result.message)
            }
        }
    }

    fun sendPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "CheckAuthenticationPassword error: ${result.message}")
                _authState.value = AuthState.Error(result.message)
            }
        }
    }

    private fun fetchCurrentUser() {
        client?.send(TdApi.GetMe()) { result ->
            if (result is TdApi.User) {
                val name = listOfNotNull(result.firstName, result.lastName)
                    .joinToString(" ").ifBlank { result.phoneNumber ?: "User" }
                _authState.value = AuthState.Ready(name)
            }
        }
    }

    fun loadAllChats() {
        if (_isLoadingChats.value) return
        _isLoadingChats.value = true
        fetchNextChatBatch()
    }

    private fun fetchNextChatBatch() {
        val currentClient = client
        if (currentClient == null) {
            _isLoadingChats.value = false
            return
        }
        currentClient.send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) { result ->
            when (result) {
                is TdApi.Ok -> {
                    refreshChatList()
                    fetchNextChatBatch()
                }
                is TdApi.Error -> {
                    Log.d(TAG, "LoadChats finished: [${result.code}] ${result.message}")
                    _isLoadingChats.value = false
                    refreshChatList()
                }
                else -> {
                    _isLoadingChats.value = false
                    refreshChatList()
                }
            }
        }
    }

    private fun refreshChatList() {
        val list = chatMap.values.map { chat ->
            val chatType = when (chat.type) {
                is TdApi.ChatTypePrivate -> ChatType.PRIVATE
                is TdApi.ChatTypeBasicGroup -> ChatType.BASIC_GROUP
                is TdApi.ChatTypeSupergroup -> {
                    val sg = chat.type as TdApi.ChatTypeSupergroup
                    if (sg.isChannel) ChatType.CHANNEL else ChatType.SUPERGROUP
                }
                else -> ChatType.UNKNOWN
            }
            ChatSummary(
                id = chat.id,
                title = chat.title.ifBlank { "Chat #${chat.id}" },
                type = chatType,
                unreadCount = chat.unreadCount
            )
        }.sortedBy { it.title.lowercase() }

        _chats.value = list
    }

    fun selectChat(chatId: Long) {
        _selectedChatId.value = chatId
    }

    private fun handleMessageSendSucceeded(message: TdApi.Message, oldMessageId: Long) {
        Log.d(TAG, "UpdateMessageSendSucceeded: oldId=$oldMessageId, newId=${message.id}")
        val pending = pendingMediaSends.remove(oldMessageId)
        if (pending != null) {
            pending.fileId?.let { fileIdToTempMessageId.remove(it) }
            pending.onProgress?.invoke(1f)
            if (pending.continuation.isActive) {
                pending.continuation.resume(message)
            }
        } else {
            recentlyCompletedSends[oldMessageId] = message
            if (recentlyCompletedSends.size > 50) {
                recentlyCompletedSends.keys.firstOrNull()?.let { recentlyCompletedSends.remove(it) }
            }
        }
    }

    private fun handleMessageSendFailed(message: TdApi.Message, oldMessageId: Long, error: TdApi.Error) {
        Log.e(TAG, "UpdateMessageSendFailed: oldId=$oldMessageId, error=[${error.code}] ${error.message}")
        val pending = pendingMediaSends.remove(oldMessageId)
        if (pending != null) {
            pending.fileId?.let { fileIdToTempMessageId.remove(it) }
            if (pending.continuation.isActive) {
                pending.continuation.resumeWithException(
                    Exception("Telegram upload failed [${error.code}]: ${error.message}")
                )
            }
        } else {
            recentlyFailedSends[oldMessageId] = error
            if (recentlyFailedSends.size > 50) {
                recentlyFailedSends.keys.firstOrNull()?.let { recentlyFailedSends.remove(it) }
            }
        }
    }

    private fun handleFileUpdate(file: TdApi.File) {
        val tempMsgId = fileIdToTempMessageId[file.id] ?: return
        val pending = pendingMediaSends[tempMsgId] ?: return

        val total = when {
            file.expectedSize > 0 -> file.expectedSize
            file.size > 0 -> file.size
            pending.expectedSize > 0 -> pending.expectedSize
            else -> 0L
        }
        val uploaded = file.remote?.uploadedSize ?: 0L

        if (total > 0 && uploaded > 0) {
            val progress = (uploaded.toFloat() / total).coerceIn(0f, 1f)
            pending.onProgress?.invoke(progress)
        }

        if (file.remote?.isUploadingCompleted == true) {
            pending.onProgress?.invoke(1f)
        }
    }

    suspend fun sendTextMessage(chatId: Long, text: String): TdApi.Message {
        val formatted = TdApi.FormattedText(text, emptyArray())
        val content = TdApi.InputMessageText().apply {
            this.text = formatted
        }
        val request = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = content
        }
        return execute(request)
    }

    private suspend fun sendMediaAndWait(
        chatId: Long,
        content: TdApi.InputMessageContent,
        fileSizeBytes: Long,
        onProgress: ((Float) -> Unit)?
    ): TdApi.Message = suspendCancellableCoroutine { cont ->
        val request = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = content
        }

        client?.send(request) { result ->
            if (result is TdApi.Error) {
                cont.resumeWithException(Exception("TDLib Send Error [${result.code}]: ${result.message}"))
                return@send
            }
            val message = result as? TdApi.Message
            if (message == null) {
                cont.resumeWithException(IllegalStateException("Unexpected response from SendMessage: $result"))
                return@send
            }

            val tempMessageId = message.id
            Log.d(TAG, "SendMessage initiated: tempId=$tempMessageId, sendingState=${message.sendingState?.javaClass?.simpleName}")

            // 1. Check if already succeeded or failed before we even registered
            val cachedSuccess = recentlyCompletedSends.remove(tempMessageId)
            if (cachedSuccess != null) {
                onProgress?.invoke(1f)
                cont.resume(cachedSuccess)
                return@send
            }
            val cachedFail = recentlyFailedSends.remove(tempMessageId)
            if (cachedFail != null) {
                cont.resumeWithException(Exception("Telegram upload failed [${cachedFail.code}]: ${cachedFail.message}"))
                return@send
            }

            // 2. Check if already sent without pending state
            if (message.sendingState == null) {
                onProgress?.invoke(1f)
                cont.resume(message)
                return@send
            }

            // 3. Check if failed immediately
            if (message.sendingState is TdApi.MessageSendingStateFailed) {
                val err = (message.sendingState as TdApi.MessageSendingStateFailed).error
                cont.resumeWithException(Exception("Telegram send failed [${err.code}]: ${err.message}"))
                return@send
            }

            // 4. Register for async completion from UpdateMessageSendSucceeded / UpdateMessageSendFailed
            val fileId = extractFileIdFromMessage(message)
            val pending = PendingMediaSend(
                tempMessageId = tempMessageId,
                fileId = fileId,
                expectedSize = fileSizeBytes,
                onProgress = onProgress,
                continuation = cont
            )

            pendingMediaSends[tempMessageId] = pending
            if (fileId != null) {
                fileIdToTempMessageId[fileId] = tempMessageId
            }

            cont.invokeOnCancellation {
                pendingMediaSends.remove(tempMessageId)
                if (fileId != null) {
                    fileIdToTempMessageId.remove(fileId)
                }
            }
        } ?: cont.resumeWithException(IllegalStateException("TDLib client is null"))
    }

    suspend fun sendVideoMessage(
        chatId: Long,
        videoPath: String,
        thumbnailPath: String?,
        caption: String,
        fileSizeBytes: Long = 0L,
        onProgress: ((Float) -> Unit)? = null
    ): TdApi.Message {
        val inputFile = TdApi.InputFileLocal(videoPath)

        var videoWidth = 0
        var videoHeight = 0
        var videoDuration = 0
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            videoDuration = (durMs / 1000).toInt()
            retriever.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract video metadata: ${e.message}")
        }

        val thumb = if (thumbnailPath != null && File(thumbnailPath).exists()) {
            var thumbWidth = 0
            var thumbHeight = 0
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(thumbnailPath, opts)
                thumbWidth = opts.outWidth
                thumbHeight = opts.outHeight
            } catch (_: Exception) {}

            TdApi.InputThumbnail().apply {
                this.thumbnail = TdApi.InputFileLocal(thumbnailPath)
                this.width = thumbWidth
                this.height = thumbHeight
            }
        } else null

        val formattedCaption = TdApi.FormattedText(caption, emptyArray())
        val content = TdApi.InputMessageVideo().apply {
            this.video = inputFile
            this.thumbnail = thumb
            this.duration = videoDuration
            this.width = videoWidth
            this.height = videoHeight
            this.supportsStreaming = true
            this.caption = formattedCaption
        }

        val size = if (fileSizeBytes > 0L) fileSizeBytes else File(videoPath).length()
        return sendMediaAndWait(chatId, content, size, onProgress)
    }

    suspend fun sendDocumentMessage(
        chatId: Long,
        filePath: String,
        thumbnailPath: String?,
        caption: String,
        fileSizeBytes: Long = 0L,
        onProgress: ((Float) -> Unit)? = null
    ): TdApi.Message {
        val inputFile = TdApi.InputFileLocal(filePath)
        val thumb = if (thumbnailPath != null && File(thumbnailPath).exists()) {
            var thumbWidth = 0
            var thumbHeight = 0
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(thumbnailPath, opts)
                thumbWidth = opts.outWidth
                thumbHeight = opts.outHeight
            } catch (_: Exception) {}

            TdApi.InputThumbnail().apply {
                this.thumbnail = TdApi.InputFileLocal(thumbnailPath)
                this.width = thumbWidth
                this.height = thumbHeight
            }
        } else null

        val formattedCaption = TdApi.FormattedText(caption, emptyArray())
        val content = TdApi.InputMessageDocument().apply {
            this.document = inputFile
            this.thumbnail = thumb
            this.disableContentTypeDetection = false
            this.caption = formattedCaption
        }

        val size = if (fileSizeBytes > 0L) fileSizeBytes else File(filePath).length()
        return sendMediaAndWait(chatId, content, size, onProgress)
    }

    private fun extractFileIdFromMessage(msg: TdApi.Message): Int? {
        return when (val content = msg.content) {
            is TdApi.MessageVideo -> content.video.video.id
            is TdApi.MessageDocument -> content.document.document.id
            else -> null
        }
    }

    fun logout() {
        client?.send(TdApi.LogOut()) {
            prefs.clear()
        }
    }

    suspend fun <T : TdApi.Object> execute(query: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            client?.send(query) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(Exception("TDLib Error [${result.code}]: ${result.message}"))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
            } ?: cont.resumeWithException(IllegalStateException("TDLib client is null"))
        }

    companion object {
        private const val TAG = "TelegramRepository"

        @Volatile
        private var instance: TelegramRepository? = null

        fun getInstance(context: Context): TelegramRepository {
            return instance ?: synchronized(this) {
                instance ?: TelegramRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
