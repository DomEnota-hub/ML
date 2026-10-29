package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramSessionLabelTest {
    @Test fun savedCookieAloneNeverShowsConnected() {
        assertEquals("Проверяем сессию", InstagramSessionLabel.from(true, null).text)
        assertEquals(InstagramSessionTone.NEUTRAL, InstagramSessionLabel.from(true, null).tone)
        val limited = SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 429, "")
        assertEquals("Сессия сохранена · не проверена",
            InstagramSessionLabel.from(true, limited).text)
    }

    @Test fun onlyConfirmedAccountShowsConnected() {
        val confirmed = SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 200,
            "{\"user\":{\"pk\":123}}")
        assertEquals("Подключён · проверено", InstagramSessionLabel.from(true, confirmed).text)
        assertEquals(InstagramSessionTone.SUCCESS, InstagramSessionLabel.from(true, confirmed).tone)
        val expired = SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 401, "")
        assertEquals("Требуется повторный вход", InstagramSessionLabel.from(true, expired).text)
        assertEquals(InstagramSessionTone.ERROR, InstagramSessionLabel.from(true, expired).tone)
    }

    @Test fun instagramChallengeCallsForAccountConfirmation() {
        val challenge = SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 400,
            "{\"message\":\"checkpoint_required\"}")
        assertEquals("Подтвердите вход в Instagram",
            InstagramSessionLabel.from(true, challenge).text)
        assertEquals(InstagramSessionTone.WARNING,
            InstagramSessionLabel.from(true, challenge).tone)
    }

    @Test fun workingMediaSessionRemainsConnectedWhenProfileFeedIsLimited() {
        val limited = SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 429, "")
        assertEquals("Подключён", InstagramSessionLabel.from(true, limited, workingMedia = true).text)
        assertEquals(InstagramSessionTone.SUCCESS,
            InstagramSessionLabel.from(true, limited, workingMedia = true).tone)
    }
}
