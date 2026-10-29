package com.domenota.medialoader.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitMozSearchClientTest {
    @Test fun buildsEncodedSearchUrl() {
        val url = HitMozSearchClient.searchUrl("Ёлка & JONY - Камин")
        assertTrue(url.startsWith("https://ru.hitmoz.org/search?q="))
        assertTrue(url.contains("%26"))
        assertTrue(url.contains("%D0%81"))
        assertFalse(url.contains(" "))
    }

    @Test fun parsesTracksAndPrioritizesExactArtistAndTitle() {
        val html = """
            <html><body><main>
              <li class="tracks__item">
                <div class="track__title">Другой трек</div>
                <div class="track__desc">Другой артист</div>
                <a href="/song/55555555">Трек</a>
                <a href="/get/music/20260410/Drugoy_Artist_-_Drugoy_Trek_55555555.mp3">Скачать</a>
              </li>
              <li class="tracks__item">
                <div class="track__title">Камин</div>
                <div class="track__desc">EMIN, JONY</div>
                <div class="track__fulltime">03:08</div>
                <a href="/song/81251971">Трек</a>
                <a href="/get/music/20260410/EMIN_JONY_-_Kamin_81251971.mp3">Скачать</a>
              </li>
            </main></body></html>
        """.trimIndent()
        val found = HitMozSearchClient.parseResults(html, "EMIN, JONY", "Камин")
        assertEquals(2, found.size)
        assertEquals("Камин", found[0].title)
        assertEquals("EMIN, JONY", found[0].artist)
        assertEquals("03:08", found[0].duration)
        assertEquals("https://ru.hitmoz.org/song/81251971", found[0].pageUrl)
        assertEquals(
            "https://ru.hitmoz.org/get/music/20260410/EMIN_JONY_-_Kamin_81251971.mp3",
            found[0].downloadUrl,
        )
    }

    @Test fun rejectsExternalHostsAndFallsBackToMp3Filename() {
        val html = """
            <html><body>
              <div><a href="https://malicious.example/get/music/BAD_-_Track_12345678.mp3">Скачать</a></div>
              <li class="tracks__item">
                <a href="/get/music/20260410/EMIN_JONY_-_Kamin_81251971.mp3">Скачать</a>
              </li>
            </body></html>
        """.trimIndent()
        val found = HitMozSearchClient.parseResults(html, "EMIN JONY", "Kamin")
        assertEquals(1, found.size)
        assertEquals("EMIN JONY", found.single().artist)
        assertEquals("Kamin", found.single().title)
        assertEquals("https://ru.hitmoz.org/song/81251971", found.single().pageUrl)
    }

    @Test fun doesNotInventSongPageWithoutSongIdOrLink() {
        val html = """<a href="/get/music/unknown.mp3">Скачать</a>"""
        assertTrue(HitMozSearchClient.parseResults(html, "A", "B").isEmpty())
    }
}
