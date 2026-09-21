package mn.blazeapps.blazegram.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mn.blazeapps.blazegram.data.model.MovieMetadata
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class Mp4MetadataTagger {

    /**
     * Injects iTunes-compatible metadata atoms (Title, Year, Genre, Description, Cover Art)
     * into an MP4 file if possible.
     */
    suspend fun tagMp4File(
        file: File,
        metadata: MovieMetadata
    ): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canWrite()) return@withContext false

        try {
            val ilstBytes = buildIlstAtom(metadata)
            if (ilstBytes.isEmpty()) return@withContext false

            // Try injecting into moov.udta.meta.ilst
            val injected = injectIlstIntoMp4(file, ilstBytes)
            if (injected) {
                Log.d(TAG, "Successfully injected iTunes metadata into ${file.name}")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not inject MP4 metadata atoms into ${file.name}: ${e.message}")
        }
        false
    }

    private fun buildIlstAtom(metadata: MovieMetadata): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        if (metadata.title.isNotBlank()) {
            writeTextAtom(dos, "\u00a9nam", metadata.title)
        }
        if (metadata.year.isNotBlank()) {
            writeTextAtom(dos, "\u00a9day", metadata.year)
        }
        if (metadata.genre.isNotBlank()) {
            writeTextAtom(dos, "\u00a9gen", metadata.genre)
        }
        if (metadata.plot.isNotBlank()) {
            writeTextAtom(dos, "desc", metadata.plot)
        }

        // Cover artwork atom
        metadata.posterLocalPath?.let { posterPath ->
            val posterFile = File(posterPath)
            if (posterFile.exists() && posterFile.length() > 0) {
                val imageBytes = posterFile.readBytes()
                val isPng = posterPath.endsWith(".png", ignoreCase = true)
                writeCoverAtom(dos, imageBytes, isPng)
            }
        }

        val itemsData = bos.toByteArray()
        if (itemsData.isEmpty()) return byteArrayOf()

        // Wrap in 'ilst' atom
        val ilstBos = ByteArrayOutputStream()
        val ilstDos = DataOutputStream(ilstBos)
        ilstDos.writeInt(itemsData.size + 8)
        ilstDos.write("ilst".toByteArray(StandardCharsets.ISO_8859_1))
        ilstDos.write(itemsData)
        return ilstBos.toByteArray()
    }

    private fun writeTextAtom(dos: DataOutputStream, fourcc: String, text: String) {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val dataAtomSize = 16 + textBytes.size
        val totalSize = 8 + dataAtomSize

        dos.writeInt(totalSize)
        dos.write(fourcc.toByteArray(StandardCharsets.ISO_8859_1))

        // Child 'data' atom
        dos.writeInt(dataAtomSize)
        dos.write("data".toByteArray(StandardCharsets.ISO_8859_1))
        dos.writeInt(1) // type UTF-8 string
        dos.writeInt(0) // locale
        dos.write(textBytes)
    }

    private fun writeCoverAtom(dos: DataOutputStream, imageBytes: ByteArray, isPng: Boolean) {
        val typeFlag = if (isPng) 14 else 13 // 13 = JPEG, 14 = PNG
        val dataAtomSize = 16 + imageBytes.size
        val totalSize = 8 + dataAtomSize

        dos.writeInt(totalSize)
        dos.write("covr".toByteArray(StandardCharsets.ISO_8859_1))

        dos.writeInt(dataAtomSize)
        dos.write("data".toByteArray(StandardCharsets.ISO_8859_1))
        dos.writeInt(typeFlag)
        dos.writeInt(0) // locale
        dos.write(imageBytes)
    }

    private fun injectIlstIntoMp4(file: File, ilstBytes: ByteArray): Boolean {
        // Build 'meta' atom wrapping 'hdlr' + 'ilst'
        val hdlrBytes = buildHdlrAtom()
        val metaBodySize = 4 + hdlrBytes.size + ilstBytes.size
        val metaTotalSize = 8 + metaBodySize

        val metaBos = ByteArrayOutputStream()
        val metaDos = DataOutputStream(metaBos)
        metaDos.writeInt(metaTotalSize)
        metaDos.write("meta".toByteArray(StandardCharsets.ISO_8859_1))
        metaDos.writeInt(0) // version + flags for full box
        metaDos.write(hdlrBytes)
        metaDos.write(ilstBytes)
        val metaBytes = metaBos.toByteArray()

        // Build 'udta' atom wrapping 'meta'
        val udtaBos = ByteArrayOutputStream()
        val udtaDos = DataOutputStream(udtaBos)
        udtaDos.writeInt(metaBytes.size + 8)
        udtaDos.write("udta".toByteArray(StandardCharsets.ISO_8859_1))
        udtaDos.write(metaBytes)
        val udtaBytes = udtaBos.toByteArray()

        // Scan MP4 boxes for moov
        RandomAccessFile(file, "rw").use { raf ->
            var offset = 0L
            val fileLength = raf.length()

            while (offset < fileLength - 8) {
                raf.seek(offset)
                val boxSize = raf.readInt().toLong() and 0xFFFFFFFFL
                val boxType = ByteArray(4)
                raf.readFully(boxType)
                val typeStr = String(boxType, StandardCharsets.ISO_8859_1)

                val actualSize = if (boxSize == 1L) {
                    raf.readLong()
                } else if (boxSize == 0L) {
                    fileLength - offset
                } else {
                    boxSize
                }

                if (typeStr == "moov") {
                    // We found moov box. We can append udta box at the end of moov if size fits
                    // Update moov size and insert udta
                    val newMoovSize = actualSize + udtaBytes.size
                    if (newMoovSize <= 0x7FFFFFFFL) {
                        val remainingBytes = ByteArray((fileLength - (offset + actualSize)).toInt())
                        raf.seek(offset + actualSize)
                        raf.readFully(remainingBytes)

                        // Write new moov size
                        raf.seek(offset)
                        raf.writeInt(newMoovSize.toInt())

                        // Write udta at end of old moov
                        raf.seek(offset + actualSize)
                        raf.write(udtaBytes)

                        // Write back any remaining data (like mdat if moov was at start)
                        if (remainingBytes.isNotEmpty()) {
                            raf.write(remainingBytes)
                        }
                        return true
                    }
                }
                offset += actualSize
            }
        }
        return false
    }

    private fun buildHdlrAtom(): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        val hdlrDataSize = 4 + 4 + 4 + 12 + 1 // version+flags, pre_defined, handler_type, reserved, name
        dos.writeInt(8 + hdlrDataSize)
        dos.write("hdlr".toByteArray(StandardCharsets.ISO_8859_1))
        dos.writeInt(0) // version & flags
        dos.writeInt(0) // pre-defined
        dos.write("mdir".toByteArray(StandardCharsets.ISO_8859_1)) // handler type: metadata
        dos.write(ByteArray(12)) // reserved
        dos.writeByte(0) // empty name string
        return bos.toByteArray()
    }

    companion object {
        private const val TAG = "Mp4MetadataTagger"
    }
}
