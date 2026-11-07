package org.openmetronome.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.openmetronome.AudioPermissionRule
import org.openmetronome.UnlockedScreenRule
import java.time.LocalDate
import org.openmetronome.data.DailyPractice

/** Isolated card only; no service, engine, vibration or user storage. */
class DailyPracticeUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule()).around(UnlockedScreenRule()).around(compose)
    @Test fun todayIsVisibleAndFailureDoesNotLookLikeZero() {
        val value = androidx.compose.runtime.mutableStateOf<DailyPractice>(DailyPractice.Ready(LocalDate.of(2026, 11, 6), 11_665_000))
        compose.setContent { MaterialTheme { DailyPracticeSummary(value.value) {} } }
        compose.onNodeWithText("Dzisiaj").assertIsDisplayed()
        compose.onNodeWithText("3 h 14 min 25 s").assertIsDisplayed()
        compose.runOnIdle { value.value = DailyPractice.Unavailable(value.value.date) }
        compose.onNodeWithText("Nie udało się odczytać dzisiejszego czasu.").assertIsDisplayed()
        compose.onNodeWithText("0 min 00 s").assertDoesNotExist()
        compose.onNodeWithText("Ponów odczyt czasu").assertIsDisplayed()
    }
}
