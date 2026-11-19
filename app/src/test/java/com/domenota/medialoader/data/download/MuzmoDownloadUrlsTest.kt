package com.domenota.medialoader.data.download

import org.junit.Assert.*
import org.junit.Test

class MuzmoDownloadUrlsTest {
    @Test fun knownUserVerifiedLinks() {
        assertTrue(MuzmoDownloadUrls.isSearchPage("https://rmr.muzmo.cc/search?q=%D0%95%D0%B2%D0%B0"))
        assertTrue(MuzmoDownloadUrls.isInfoPage("https://rmr.muzmo.cc/info?id=82447323"))
        assertTrue(MuzmoDownloadUrls.isMp3("https://rmr.muzmo.cc/get/music/20261006/Eva_Vlasova_-_Vchera_82447323.mp3"))
        assertTrue(MuzmoDownloadUrls.isMp3("https://rmr.muzmo.cc/get/music/20260922/DJ_NARODNII_-_Ty_moya_82341560.mp3"))
    }

    @Test fun searchUrlEncodesUnicode() {
        assertEquals("https://rmr.muzmo.cc/search?q=%D0%95%D0%B2%D0%B0", MuzmoDownloadUrls.searchUrl("Ева"))
    }

    @Test fun rejectUnexpectedHostsPathsAndSchemes() {
        val invalid = listOf(
            "http://rmr.muzmo.cc/get/music/20261006/test.mp3",
            "https://rmr.muzmo.cc.evil.org/get/music/20261006/test.mp3",
            "https://evil@rmr.muzmo.cc/get/music/20261006/test.mp3",
            "https://rmr.muzmo.cc:444/get/music/20261006/test.mp3",
            "https://rmr.muzmo.cc/get/music/20261006/../test.mp3",
            "https://rmr.muzmo.cc/get/music/a/test.mp3",
            "https://rmr.muzmo.cc/info?id=82447323",
        )
        invalid.forEach { assertFalse(it, MuzmoDownloadUrls.isMp3(it)) }
        assertFalse(MuzmoDownloadUrls.isInfoPage("https://rmr.muzmo.cc/info?id=bad"))
        assertFalse(MuzmoDownloadUrls.isTrustedRedirect("https://unknown-cdn.test/music.mp3"))
        assertFalse(MuzmoDownloadUrls.isSearchPage("https://ru.hitmoz.org/search?q=Eva"))
    }

    @Test fun observed302ToDs2IsAcceptedButCannotStartDirectDownload() {
        val original=java.net.URL("https://rmr.muzmo.cc/get/music/20261119/MUKKA_-_Vselennaya_111.mp3")
        val redirected=java.net.URL(original,"https://ds2.mzmdl.com/get/music/20261119/MUKKA_-_Vselennaya_111.mp3")
        assertTrue(MuzmoDownloadUrls.isMp3(original.toString()))
        assertTrue(MuzmoDownloadUrls.isTrustedRedirect(redirected.toString()))
        assertFalse(MuzmoDownloadUrls.isMp3(redirected.toString()))
    }

    @Test fun ds2HostIsExactHttpsOnlyAndCannotBeUsedForOpenRedirects() {
        for(url in listOf(
            "https://ds3.mzmdl.com/get/music/20261119/song.mp3",
            "https://ds2.mzmdl.com.evil.test/get/music/20261119/song.mp3",
            "https://evil@ds2.mzmdl.com/get/music/20261119/song.mp3",
            "http://ds2.mzmdl.com/get/music/20261119/song.mp3",
            "https://ds2.mzmdl.com:444/get/music/20261119/song.mp3",
            "https://ds2.mzmdl.com/search?q=MUKKA",
            "https://ds2.mzmdl.com/get/music/20261119/../song.mp3",
            "https://ds2.mzmdl.com/get/music/unknown/song.mp3",
            "https://ds2.mzmdl.com/get/music/20261119/song.html",
            "https://hitmoz.org/get/music/20261119/song.mp3",
        )) assertFalse(url,MuzmoDownloadUrls.isTrustedRedirect(url))
    }

    @Test fun acceptObservedMuzmoAudioRedirectOnly() {
        assertTrue(MuzmoDownloadUrls.isTrustedRedirect("https://mzmdl.com/get/music/20261006/Eva_Vlasova_-_Vchera_82447323.mp3"))
        assertFalse(MuzmoDownloadUrls.isTrustedRedirect("https://mzmdl.com/search?q=Eva"))
        assertFalse(MuzmoDownloadUrls.isTrustedRedirect("https://mzmdl.com/file.html"))
        assertFalse(MuzmoDownloadUrls.isTrustedRedirect("http://mzmdl.com/get/music/file.mp3"))
        assertFalse(MuzmoDownloadUrls.isTrustedRedirect("https://mzmdl.com.evil.test/get/music/file.mp3"))
    }
}
