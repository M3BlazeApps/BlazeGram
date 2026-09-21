package mn.blazeapps.blazegram.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Mp4Converter(private val context: Context) {

    /**
     * Converts or transmuxes input media file to MP4 using AndroidX Media3 Transformer.
     * Returns the converted File.
     */
    suspend fun convertToMp4(
        inputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): File = withContext(Dispatchers.Main) {
        val outputDir = File(context.cacheDir, "converted_mp4")
        if (!outputDir.exists()) outputDir.mkdirs()

        val outputFileName = "${inputFile.nameWithoutExtension}_converted_${System.currentTimeMillis()}.mp4"
        val outputFile = File(outputDir, outputFileName)

        suspendCancellableCoroutine<File> { continuation ->
            val progressHolder = ProgressHolder()
            var isFinished = false

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        isFinished = true
                        onProgress?.invoke(1f)
                        Log.d(TAG, "Conversion completed: ${outputFile.absolutePath}")
                        continuation.resume(outputFile)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        isFinished = true
                        Log.e(TAG, "Conversion failed: ${exportException.message}", exportException)
                        if (outputFile.exists()) outputFile.delete()
                        continuation.resumeWithException(exportException)
                    }
                })
                .build()

            val mediaItem = MediaItem.fromUri(inputFile.toURI().toString())
            transformer.start(mediaItem, outputFile.absolutePath)

            // Progress polling loop on main looper / handler
            val handler = Handler(Looper.getMainLooper())
            val progressRunnable = object : Runnable {
                override fun run() {
                    if (!isFinished && continuation.isActive) {
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val p = (progressHolder.progress / 100f).coerceIn(0f, 1f)
                            onProgress?.invoke(p)
                        }
                        handler.postDelayed(this, 300)
                    }
                }
            }
            handler.post(progressRunnable)

            continuation.invokeOnCancellation {
                isFinished = true
                handler.removeCallbacks(progressRunnable)
                try {
                    transformer.cancel()
                } catch (e: Exception) {
                    Log.w(TAG, "Cancel error: ${e.message}")
                }
                if (outputFile.exists()) {
                    outputFile.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "Mp4Converter"
    }
}
