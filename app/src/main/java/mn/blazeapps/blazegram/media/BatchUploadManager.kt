package mn.blazeapps.blazegram.media

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import mn.blazeapps.blazegram.data.TelegramRepository
import mn.blazeapps.blazegram.data.model.BatchUploadItem
import mn.blazeapps.blazegram.data.model.FolderUploadSummary
import mn.blazeapps.blazegram.data.model.ItemUploadStatus
import mn.blazeapps.blazegram.data.model.MovieMetadata
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatchUploadManager(
    private val context: Context,
    private val repository: TelegramRepository
) {
    private val itunesService = iTunesMetadataService(context)
    private val mp4Converter = Mp4Converter(context)
    private val mp4Tagger = Mp4MetadataTagger()

    private val _summary = MutableStateFlow<FolderUploadSummary?>(null)
    val summary: StateFlow<FolderUploadSummary?> = _summary.asStateFlow()

    fun initializeBatch(folderName: String, items: List<BatchUploadItem>) {
        val totalSize = items.sumOf { it.size }
        _summary.value = FolderUploadSummary(
            folderName = folderName,
            totalCount = items.size,
            totalSizeBytes = totalSize,
            items = items,
            isUploading = false,
            isCompleted = false,
            activeIndex = -1,
            logs = listOf("Folder \"$folderName\" loaded with ${items.size} files (${formatFileSize(totalSize)}).")
        )
    }

    suspend fun executeUpload(
        chatId: Long,
        convertToMp4Enabled: Boolean,
        fetchItunesMetadataEnabled: Boolean
    ) = withContext(Dispatchers.IO) {
        val initialSummary = _summary.value ?: return@withContext
        val items = initialSummary.items.toMutableList()
        val totalCount = items.size
        val totalSize = initialSummary.totalSizeBytes
        val folderName = initialSummary.folderName
        val startTime = System.currentTimeMillis()

        _summary.value = initialSummary.copy(isUploading = true, isCompleted = false)
        addLog("Starting folder upload process for \"$folderName\"...")

        // ── Phase 1: Upload Start Folder Text Message ───────────────────────
        val startMessageText = buildString {
            appendLine("📁 [START FOLDER UPLOAD]")
            appendLine("━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📂 Folder: $folderName")
            appendLine("📊 Total Files: $totalCount (${formatFileSize(totalSize)})")
            appendLine("⚙️ MP4 Conversion: ${if (convertToMp4Enabled) "Enabled" else "Disabled"}")
            appendLine("🏷️ iTunes Metadata: ${if (fetchItunesMetadataEnabled) "Enabled" else "Disabled"}")
            appendLine("⏰ Started at: ${currentTimestamp()}")
        }

        try {
            repository.sendTextMessage(chatId, startMessageText)
            addLog("Sent Start Folder log message to Telegram.")
        } catch (e: Exception) {
            addLog("⚠️ Failed to send start log: ${e.message}")
        }

        var successCount = 0

        // ── Phase 2: Sequential File Upload Pipeline ────────────────────────
        for (index in items.indices) {
            val item = items[index]
            _summary.value = _summary.value?.copy(activeIndex = index)

            updateItem(index) {
                it.copy(status = ItemUploadStatus.STAGING, statusMessage = "Staging file...")
            }

            // 1. Stage file to cache
            val stagedFile = try {
                stageFileFromUri(item.uri, item.originalFileName)
            } catch (e: Exception) {
                Log.e(TAG, "Error staging file: ${e.message}", e)
                updateItem(index) {
                    it.copy(
                        status = ItemUploadStatus.FAILED,
                        statusMessage = "Staging failed",
                        errorMessage = e.message
                    )
                }
                addLog("❌ Staging failed for ${item.originalFileName}: ${e.message}")
                continue
            }

            var finalUploadFile = stagedFile
            var metadata: MovieMetadata? = null

            // 2. Video conversion to MP4
            if (item.isVideo && convertToMp4Enabled && !item.extension.equals("mp4", ignoreCase = true)) {
                updateItem(index) {
                    it.copy(status = ItemUploadStatus.CONVERTING_MP4, statusMessage = "Converting to MP4...")
                }
                addLog("🔄 Converting ${item.originalFileName} to MP4 container via Media3 Transformer...")

                try {
                    val converted = mp4Converter.convertToMp4(stagedFile) { convProgress ->
                        updateItem(index) { it.copy(progress = convProgress * 0.4f) }
                    }
                    finalUploadFile = converted
                    addLog("✅ Conversion complete: ${finalUploadFile.name}")
                    if (stagedFile.exists()) {
                        stagedFile.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MP4 conversion error for ${item.originalFileName}: ${e.message}")
                    addLog("⚠️ Conversion error, proceeding with original file: ${e.message}")
                }
            }

            // 3. iTunes Metadata
            if (item.isVideo && fetchItunesMetadataEnabled) {
                updateItem(index) {
                    it.copy(status = ItemUploadStatus.FETCHING_METADATA, statusMessage = "Fetching iTunes info...")
                }
                addLog("🔍 Searching iTunes Movie Database for \"${item.originalFileName}\"...")

                metadata = itunesService.fetchMetadataForFile(item.originalFileName)
                if (metadata != null) {
                    addLog("✨ Found iTunes match: \"${metadata.title}\" (${metadata.year}) [${metadata.genre}]")
                    updateItem(index) { it.copy(metadata = metadata) }

                    // Tag MP4 file
                    if (finalUploadFile.extension.equals("mp4", ignoreCase = true)) {
                        try {
                            mp4Tagger.tagMp4File(finalUploadFile, metadata)
                        } catch (e: Exception) {
                            Log.w(TAG, "Tagging error: ${e.message}")
                        }
                    }
                } else {
                    addLog("ℹ️ No iTunes movie match found for \"${item.originalFileName}\".")
                }
            }

            // 4. Send Per-File Text Message Log
            val fileLogText = buildString {
                appendLine("🎬 [File ${index + 1}/$totalCount: Processing Complete]")
                appendLine("━━━━━━━━━━━━━━━━━━━━━")
                if (metadata != null) {
                    appendLine("🎥 Title: ${metadata.title} ${if (metadata.year.isNotBlank()) "(${metadata.year})" else ""}")
                    if (metadata.genre.isNotBlank()) appendLine("🎭 Genre: ${metadata.genre}")
                    if (metadata.plot.isNotBlank()) appendLine("📝 Synopsis: ${metadata.plot.take(280)}...")
                } else {
                    appendLine("📄 File: ${item.originalFileName}")
                }
                appendLine("📁 File: ${item.originalFileName} (${formatFileSize(finalUploadFile.length())})")
                appendLine("🏷️ Format: ${finalUploadFile.extension.uppercase()} | ${if (metadata != null) "iTunes Enriched" else "Standard"}")
            }

            try {
                repository.sendTextMessage(chatId, fileLogText)
                addLog("Sent file log message for [${index + 1}/$totalCount].")
            } catch (e: Exception) {
                addLog("⚠️ Failed to send file log: ${e.message}")
            }

            // 5. Upload File to Telegram
            updateItem(index) {
                it.copy(
                    status = ItemUploadStatus.UPLOADING,
                    statusMessage = "Uploading file...",
                    processedFile = finalUploadFile
                )
            }

            val caption = buildString {
                if (metadata != null) {
                    append("${metadata.title} (${metadata.year})\n")
                    if (metadata.genre.isNotBlank()) append("Genre: ${metadata.genre}\n")
                }
                append(item.originalFileName)
            }

            try {
                val isMp4 = finalUploadFile.extension.equals("mp4", ignoreCase = true)
                val isConverted = convertToMp4Enabled && !item.extension.equals("mp4", ignoreCase = true)

                if (item.isVideo && isMp4) {
                    try {
                        repository.sendVideoMessage(
                            chatId = chatId,
                            videoPath = finalUploadFile.absolutePath,
                            thumbnailPath = metadata?.posterLocalPath,
                            caption = caption,
                            fileSizeBytes = finalUploadFile.length(),
                            onProgress = { uploadProgress ->
                                updateItem(index) {
                                    it.copy(
                                        progress = if (isConverted) {
                                            0.4f + (uploadProgress * 0.6f)
                                        } else {
                                            uploadProgress
                                        },
                                        statusMessage = "Uploading: ${(uploadProgress * 100).toInt()}%"
                                    )
                                }
                            }
                        )
                    } catch (videoEx: Exception) {
                        Log.w(TAG, "Video message send failed (${videoEx.message}), retrying as document...")
                        addLog("⚠️ Video stream format error (${videoEx.message}), uploading as document...")
                        repository.sendDocumentMessage(
                            chatId = chatId,
                            filePath = finalUploadFile.absolutePath,
                            thumbnailPath = metadata?.posterLocalPath,
                            caption = caption,
                            fileSizeBytes = finalUploadFile.length(),
                            onProgress = { uploadProgress ->
                                updateItem(index) {
                                    it.copy(
                                        progress = if (isConverted) {
                                            0.4f + (uploadProgress * 0.6f)
                                        } else {
                                            uploadProgress
                                        },
                                        statusMessage = "Uploading: ${(uploadProgress * 100).toInt()}%"
                                    )
                                }
                            }
                        )
                    }
                } else {
                    repository.sendDocumentMessage(
                        chatId = chatId,
                        filePath = finalUploadFile.absolutePath,
                        thumbnailPath = metadata?.posterLocalPath,
                        caption = caption,
                        fileSizeBytes = finalUploadFile.length(),
                        onProgress = { uploadProgress ->
                            updateItem(index) {
                                it.copy(
                                    progress = uploadProgress,
                                    statusMessage = "Uploading: ${(uploadProgress * 100).toInt()}%"
                                )
                            }
                        }
                    )
                }

                updateItem(index) {
                    it.copy(
                        status = ItemUploadStatus.COMPLETED,
                        progress = 1f,
                        statusMessage = "Completed"
                    )
                }
                successCount++
                addLog("✅ Successfully uploaded [${index + 1}/$totalCount]: ${item.originalFileName}")
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed for ${item.originalFileName}: ${e.message}", e)
                updateItem(index) {
                    it.copy(
                        status = ItemUploadStatus.FAILED,
                        statusMessage = "Upload failed",
                        errorMessage = e.message
                    )
                }
                addLog("❌ Upload failed for ${item.originalFileName}: ${e.message} (Continuing with remaining files...)")
                try {
                    repository.sendTextMessage(
                        chatId,
                        "⚠️ [Upload Error] Could not upload \"${item.originalFileName}\": ${e.message}. Continuing with remaining queue..."
                    )
                } catch (_: Exception) {}
            } finally {
                // Remove temporary converted MP4, staged file, and cached poster immediately after upload
                try {
                    if (finalUploadFile != stagedFile && finalUploadFile.exists()) {
                        val deletedConv = finalUploadFile.delete()
                        if (deletedConv) {
                            Log.d(TAG, "Deleted temp converted MP4: ${finalUploadFile.name}")
                        }
                    }
                    if (stagedFile.exists()) {
                        val deletedStage = stagedFile.delete()
                        if (deletedStage) {
                            Log.d(TAG, "Deleted temp staged file: ${stagedFile.name}")
                        }
                    }
                    metadata?.posterLocalPath?.let { posterPath ->
                        val posterFile = File(posterPath)
                        if (posterFile.exists()) {
                            posterFile.delete()
                        }
                    }
                } catch (cleanupEx: Exception) {
                    Log.w(TAG, "Cleanup error for ${item.originalFileName}: ${cleanupEx.message}")
                }
            }
        }

        // ── Phase 3: Upload End Folder Text Message ─────────────────────────
        val elapsedTime = System.currentTimeMillis() - startTime
        val endMessageText = buildString {
            appendLine("✅ [END FOLDER UPLOAD]")
            appendLine("━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📂 Folder: $folderName")
            appendLine("🎉 Status: Completed")
            appendLine("📊 Uploaded: $successCount of $totalCount files")
            appendLine("⏱️ Elapsed: ${formatDuration(elapsedTime)}")
            appendLine("🏁 Finished at: ${currentTimestamp()}")
        }

        try {
            repository.sendTextMessage(chatId, endMessageText)
            addLog("Sent End Folder summary log message to Telegram.")
        } catch (e: Exception) {
            addLog("⚠️ Failed to send end log: ${e.message}")
        }

        cleanupTempDirectories()

        _summary.value = _summary.value?.copy(
            isUploading = false,
            isCompleted = true,
            activeIndex = -1
        )
        addLog("Batch folder upload complete! Successfully sent $successCount of $totalCount files. All temporary files purged.")
    }

    private fun cleanupTempDirectories() {
        try {
            val stagingDir = File(context.cacheDir, "upload_staging")
            stagingDir.listFiles()?.forEach { if (it.isFile) it.delete() }

            val convertedDir = File(context.cacheDir, "converted_mp4")
            convertedDir.listFiles()?.forEach { if (it.isFile) it.delete() }

            val postersDir = File(context.cacheDir, "itunes_posters")
            postersDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning temp directories: ${e.message}")
        }
    }

    private fun stageFileFromUri(uri: Uri, fileName: String): File {
        val stagingDir = File(context.cacheDir, "upload_staging")
        if (!stagingDir.exists()) stagingDir.mkdirs()

        val safeName = fileName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val targetFile = File(stagingDir, "stage_${System.currentTimeMillis()}_$safeName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open input stream for $uri")

        return targetFile
    }

    private fun updateItem(index: Int, block: (BatchUploadItem) -> BatchUploadItem) {
        val curr = _summary.value ?: return
        val items = curr.items.toMutableList()
        if (index in items.indices) {
            items[index] = block(items[index])
            _summary.value = curr.copy(items = items)
        }
    }

    private fun addLog(log: String) {
        val curr = _summary.value ?: return
        val updatedLogs = curr.logs + log
        _summary.value = curr.copy(logs = updatedLogs)
    }

    private fun currentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return if (hours > 0) "${hours}h ${minutes}m ${seconds}s" else "${minutes}m ${seconds}s"
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    companion object {
        private const val TAG = "BatchUploadManager"
    }
}
