package mn.blazeapps.blazegram.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mn.blazeapps.blazegram.data.model.MovieMetadata
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder

class iTunesMetadataService(private val context: Context) {

    suspend fun fetchMetadataForFile(fileName: String): MovieMetadata? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanFileName(fileName.substringBeforeLast('.'))
        if (cleanTitle.isBlank()) return@withContext null

        try {
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedTitle&entity=movie&limit=1"
            val response = URL(url).readText()
            val json = JSONObject(response)

            if (json.optInt("resultCount", 0) > 0) {
                val result = json.getJSONArray("results").getJSONObject(0)
                val trackName = result.optString("trackName", cleanTitle)
                val releaseDate = result.optString("releaseDate", "").take(4)
                val genre = result.optString("primaryGenreName", "")
                val plot = result.optString("longDescription", result.optString("shortDescription", ""))
                val artworkRaw = result.optString("artworkUrl100", "")
                val highResPosterUrl = if (artworkRaw.isNotBlank()) {
                    artworkRaw.replace("100x100bb", "600x600bb")
                } else ""

                var localPosterPath: String? = null
                if (highResPosterUrl.isNotBlank()) {
                    localPosterPath = downloadPosterToCache(highResPosterUrl, trackName)
                }

                return@withContext MovieMetadata(
                    title = trackName,
                    year = releaseDate,
                    genre = genre,
                    plot = plot,
                    posterUrl = highResPosterUrl,
                    posterLocalPath = localPosterPath
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private fun downloadPosterToCache(posterUrl: String, title: String): String? {
        return try {
            val postersDir = File(context.cacheDir, "itunes_posters")
            if (!postersDir.exists()) postersDir.mkdirs()

            val safeName = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val targetFile = File(postersDir, "poster_${safeName}_${System.currentTimeMillis()}.jpg")

            URL(posterUrl).openStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        fun cleanFileName(rawName: String): String {
            var clean = rawName.replace(".", " ").replace("_", " ").replace("-", " ")
            val tagsToRemove = listOf(
                "1080p", "720p", "480p", "2160p", "4k", "bluray", "web-dl", "webrip", "hdrip",
                "x264", "x265", "hevc", "aac", "dts-hd", "dts", "truehd", "dual audio", "hindi", "english", "remux",
                "extended", "unrated", "proper", "repack", "imax", "hdr", "10bit", "esub", "hd"
            )
            for (tag in tagsToRemove) {
                clean = clean.replace(Regex("(?i)\\b$tag\\b"), "")
            }
            // Remove text in brackets like [XYZ] or (XYZ)
            clean = clean.replace(Regex("\\[.*?\\]"), "")
            clean = clean.replace(Regex("\\(.*?\\)"), "")

            return clean.replace(Regex("\\s+"), " ").trim()
        }
    }
}
