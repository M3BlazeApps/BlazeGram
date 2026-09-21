package mn.blazeapps.blazegram.data.model

import android.net.Uri
import java.io.File

sealed class AuthState {
    object Initializing : AuthState()
    object NeedParameters : AuthState()
    object NeedPhoneNumber : AuthState()
    object NeedCode : AuthState()
    object NeedPassword : AuthState()
    data class Ready(val userFirstName: String = "") : AuthState()
    object LoggingOut : AuthState()
    object Closed : AuthState()
    data class Error(val message: String) : AuthState()
}

enum class ChatType {
    CHANNEL,
    SUPERGROUP,
    BASIC_GROUP,
    PRIVATE,
    UNKNOWN
}

data class ChatSummary(
    val id: Long,
    val title: String,
    val type: ChatType,
    val unreadCount: Int = 0
)

data class MovieMetadata(
    val title: String,
    val year: String,
    val genre: String,
    val plot: String,
    val posterUrl: String,
    val posterLocalPath: String? = null
)

enum class ItemUploadStatus {
    PENDING,
    STAGING,
    CONVERTING_MP4,
    FETCHING_METADATA,
    UPLOADING,
    COMPLETED,
    FAILED
}

data class BatchUploadItem(
    val id: String,
    val uri: Uri,
    val originalFileName: String,
    val size: Long,
    val isVideo: Boolean,
    val extension: String,
    val status: ItemUploadStatus = ItemUploadStatus.PENDING,
    val progress: Float = 0f,
    val statusMessage: String = "Queued",
    val errorMessage: String? = null,
    val processedFile: File? = null,
    val metadata: MovieMetadata? = null
)

data class FolderUploadSummary(
    val folderName: String,
    val totalCount: Int,
    val totalSizeBytes: Long,
    val items: List<BatchUploadItem> = emptyList(),
    val isUploading: Boolean = false,
    val isCompleted: Boolean = false,
    val activeIndex: Int = -1,
    val logs: List<String> = emptyList()
)
