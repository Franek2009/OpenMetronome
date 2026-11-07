package org.openmetronome.diagnostics

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.openmetronome.*
import org.openmetronome.domain.audio.PcmTraceKind
import java.util.UUID

/** TestApplication blocks hardware; seed and delete only this test's report UUID. */
class TraceReportsUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule())
        .around(UnlockedScreenRule()).around(compose)

    @Test fun devReportListRefreshesAndDeleteCancellationPreservesFixture() {
        val data = (compose.activity.application as TestApplication).data
        val store = DevReportStorage.store(data.diagnosticReportDirectory)
        compose.waitUntil(10000) { store.status.value.ready && data.storageReady.value }
        val capture = store.begin(TraceSessionKey(UUID.randomUUID(), 1))!!
        val reports = TraceReports(store)
        try {
            capture.trace.record(PcmTraceKind.BEAT, 10); capture.trace.close(); capture.finishControls()
            compose.waitUntil(10000) { store.status.value.active == 0 }
            compose.onAllNodesWithText("Ustawienia").onFirst().performClick()
            compose.onNodeWithText("Odśwież raporty DEV").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(capture.reportId.toString()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Usuń raport").performScrollTo().performClick()
            compose.onNodeWithText("Usunąć raport DEV?").assertIsDisplayed()
            compose.onNodeWithText("Anuluj").performClick()
            compose.onNodeWithText("Eksportuj ZIP").performScrollTo().assertIsEnabled()
            assertEquals(capture.reportId, runBlocking { reports.list() }.single().id)
            assertFalse(data.playbackBusy.value)
        } finally {
            capture.trace.close(); capture.finishControls()
            compose.waitUntil(10000) { store.status.value.active == 0 }
            runBlocking { reports.delete(capture.reportId) }
        }
    }
}
