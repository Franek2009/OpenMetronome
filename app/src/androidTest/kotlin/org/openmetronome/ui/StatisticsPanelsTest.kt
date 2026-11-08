package org.openmetronome.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.openmetronome.AudioPermissionRule
import org.openmetronome.UnlockedScreenRule
import org.openmetronome.data.*
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.ui.theme.OpenMetronomeTheme
import java.time.*

class StatisticsPanelsTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule()).around(UnlockedScreenRule()).around(compose)
    private val today = LocalDate.of(2026, 11, 7)
    private fun session(id: String, interrupted: Boolean = false) = PracticeSession(id,
        today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), today.atTime(12, 5).toInstant(ZoneOffset.UTC).toEpochMilli(),
        300000, true, interrupted, "UTC", ConfigCodec.encode(MetronomeConfig()))

    @Test fun legacyPracticeIsUnknownAnalysisRatherThanInventedZeroTempo() {
        val stats = PracticeAnalyticsCalculator.calculate(listOf(session("old")), StatisticsPreferences(), today)
        compose.setContent { OpenMetronomeTheme { PracticeAnalysisPanel(stats) } }
        compose.onNodeWithText("Znany przebieg: 0 min 00 s").assertIsDisplayed()
        compose.onNodeWithText("Bez pełnego przebiegu: 5 min 00 s").assertIsDisplayed()
        compose.onNodeWithText("Tempo wynikowe").assertDoesNotExist()
    }

    @Test fun thresholdDialogRejectsInvalidValuesAndAppliesOnlyExplicitConfirmation() {
        var applied: StatisticsPreferences? = null
        compose.setContent { OpenMetronomeTheme { StatisticsThresholdDialog(StatisticsPreferences(), {}, { applied = it }) } }
        val sessionInput = compose.onNodeWithText("Minimum sesji, sekundy (0–3600)")
        sessionInput.performTextReplacement("9999")
        compose.onNodeWithText("Zastosuj progi").assertIsNotEnabled()
        compose.runOnIdle { assertNull(applied) }
        sessionInput.performTextReplacement("30")
        compose.onNodeWithText("Zastosuj progi").performClick()
        compose.runOnIdle { assertEquals(StatisticsPreferences(30, 5), applied) }
    }
}
