package mn.blazeapps.blazegram.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.blazeapps.blazegram.data.TelegramRepository
import mn.blazeapps.blazegram.data.UserPreferences
import mn.blazeapps.blazegram.data.model.BatchUploadItem
import mn.blazeapps.blazegram.data.model.FolderUploadSummary
import mn.blazeapps.blazegram.media.BatchUploadManager
import java.util.UUID

class UploadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TelegramRepository.getInstance(application)
    private val batchUploadManager = BatchUploadManager(application, repository)
    private val prefs = UserPreferences(application)

    val folderSummary: StateFlow<FolderUploadSummary?> = batchUploadManager.summary

    var convertToMp4 by mutableStateOf(prefs.convertToMp4)
        private set

    var fetchItunesMetadata by mutableStateOf(prefs.fetchItunesMetadata)
        private set

    var isScanningFolder by mutableStateOf(false)
        private set

    fun setConvertToMp4Enabled(enabled: Boolean) {
        convertToMp4 = enabled
        prefs.convertToMp4 = enabled
    }

    fun setFetchItunesMetadataEnabled(enabled: Boolean) {
        fetchItunesMetadata = enabled
        prefs.fetchItunesMetadata = enabled
    }

    fun processSelectedFolder(treeUri: Uri) {
        viewModelScope.launch {
            isScanningFolder = true
            withContext(Dispatchers.IO) {
                val rootDoc = DocumentFile.fromTreeUri(getApplication(), treeUri)
                val folderName = rootDoc?.name ?: "Selected_Folder"
                val items = mutableListOf<BatchUploadItem>()

                rootDoc?.let { doc ->
                    scanDirectory(doc, "", items)
                }

                batchUploadManager.initializeBatch(folderName, items)
            }
            isScanningFolder = false
        }
    }

    private fun scanDirectory(dir: DocumentFile, currentPath: String, outList: MutableList<BatchUploadItem>) {
        val files = dir.listFiles()
        for (file in files) {
            val name = file.name ?: "file_${UUID.randomUUID()}"
            val relativePath = if (currentPath.isEmpty()) name else "$currentPath/$name"
            if (file.isDirectory) {
                scanDirectory(file, relativePath, outList)
            } else if (file.isFile && file.length() > 0) {
                val ext = name.substringAfterLast('.', "").lowercase()
                val isVideo = VIDEO_EXTENSIONS.contains(ext)

                outList.add(
                    BatchUploadItem(
                        id = UUID.randomUUID().toString(),
                        uri = file.uri,
                        originalFileName = relativePath,
                        size = file.length(),
                        isVideo = isVideo,
                        extension = ext
                    )
                )
            }
        }
    }

    fun startUpload(chatId: Long) {
        viewModelScope.launch {
            batchUploadManager.executeUpload(
                chatId = chatId,
                convertToMp4Enabled = convertToMp4,
                fetchItunesMetadataEnabled = fetchItunesMetadata
            )
        }
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "mov", "webm", "avi", "flv", "wmv", "m4v",
            "3gp", "ts", "mpg", "mpeg", "m2ts", "vob", "ogv", "divx", "asf"
        )
    }
}
