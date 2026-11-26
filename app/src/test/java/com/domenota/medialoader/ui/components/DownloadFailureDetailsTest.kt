package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadFailureDetailsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun longFailedDownloadMessageIsAlwaysAvailableInFullNotOnlyTwoTruncatedLines() {
        val reason="Muzmo перенаправил файл на неподтверждённый адрес; проверка домена не пройдена. " +
            "Первоначальный URL сохранён, сетевой доступ не подтверждён. " +
            "Технические сведения для диагностики: HTTP 302, redirect denied."
        compose.setContent {
            MaterialTheme {
                DownloadRow(
                    item=DownloadUiItem(id="long-error",title="audio.mp3",subtitle=reason,sizeLabel=null,
                        state=DownloadUiState.FAILED),
                    onOpen={},onCancel={},
                )
            }
        }
        compose.onNodeWithText("Показать ошибку полностью").assertDoesNotExist()
        compose.onNodeWithText("Подробности ошибки загрузки").assertDoesNotExist()
        compose.onNodeWithText(reason).assertExists()
        org.junit.Assert.assertEquals(1, compose.onAllNodesWithText(reason).fetchSemanticsNodes().size)
    }
}
