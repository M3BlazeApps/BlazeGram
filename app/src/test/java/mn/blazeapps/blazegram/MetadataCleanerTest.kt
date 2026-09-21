package mn.blazeapps.blazegram

import mn.blazeapps.blazegram.media.iTunesMetadataService
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataCleanerTest {

    @Test
    fun testCleanFileName_removesReleaseTags() {
        val raw1 = "Spider-Man.Across.the.Spider-Verse.2023.1080p.WEBRip.x264.AAC-[YTS.MX]"
        val clean1 = iTunesMetadataService.cleanFileName(raw1)
        assertEquals("Spider Man Across the Spider Verse 2023", clean1)

        val raw2 = "Inception.2010.Bluray.2160p.4K.HDR.HEVC.DTS-HD.MA.5.1"
        val clean2 = iTunesMetadataService.cleanFileName(raw2)
        assertEquals("Inception 2010 MA 5 1", clean2)

        val raw3 = "[Erai-raws] Suzume no Tojimari (2022) [1080p][HEVC]"
        val clean3 = iTunesMetadataService.cleanFileName(raw3)
        assertEquals("Suzume no Tojimari", clean3)
    }
}
