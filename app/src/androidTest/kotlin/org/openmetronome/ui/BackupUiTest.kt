package org.openmetronome.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.openmetronome.*
import org.openmetronome.audio.PlaybackStatus
import org.openmetronome.data.*
import org.openmetronome.domain.model.*
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class BackupUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule()).around(UnlockedScreenRule()).around(compose)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var data: AppData
    private lateinit var model: MetronomeViewModel
    private fun uri(name: String) = DocumentsContract.buildDocumentUri(instrumentation.targetContext.packageName + ".backup-documents", name)

    @Before fun setUp() {
        data = (compose.activity.application as TestApplication).data
        model = ViewModelProvider(compose.activity)[MetronomeViewModel::class.java]
        model.stop()
        compose.waitUntil(10000) { !data.playbackBusy.value && model.state.value.audio.status == PlaybackStatus.STOPPED }
        runBlocking(Dispatchers.IO) { data.ready.await(); data.awaitWrites(); data.database.clearAllTables(); data.samples.load() }
        compose.runOnIdle { model.setConfig(MetronomeConfig(tempo = Tempo(91))) }
        runBlocking(Dispatchers.IO) { data.awaitWrites() }
        val config = ConfigCodec.encode(MetronomeConfig(tempo = Tempo(137)))
        val archive = ByteArrayOutputStream().also { BackupArchive.write(BackupSnapshot(config,
            listOf(Preset("backup", "Z kopii", config, 1, 1)), emptyList(), emptyList(), emptyList(), emptyMap()), it) }.toByteArray()
        instrumentation.targetContext.contentResolver.openOutputStream(uri("roundtrip.ombak"), "wt")!!.use { it.write(archive) }
        instrumentation.targetContext.contentResolver.openOutputStream(uri("invalid.ombak"), "wt")!!.use { it.write(byteArrayOf(1, 2)) }
        compose.onNodeWithText("Statystyki").performClick()
    }
    @After fun stopped() {
        if (::model.isInitialized) compose.runOnIdle { assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status) }
    }
    private fun launchResult(action: String, name: String?, click: () -> Unit) {
        val filter = IntentFilter(action).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val result = if (name == null) Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            else Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri(name)))
        val monitor = Instrumentation.ActivityMonitor(filter, result, true)
        instrumentation.addMonitor(monitor)
        try { click(); compose.waitForIdle(); assertEquals(1, monitor.hits) }
        finally { instrumentation.removeMonitor(monitor) }
    }
    private fun importResult(name: String?) = launchResult(Intent.ACTION_OPEN_DOCUMENT, name) {
        compose.onNodeWithText("Przywróć kopię").performScrollTo().performClick()
    }

    @Test fun contentUriImportConfirmsSurvivesRotationAndRestores() {
        importResult("roundtrip.ombak")
        compose.onNodeWithText("Przywrócić kopię?").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Przywrócić kopię?").assertIsDisplayed()
        compose.onNodeWithText("Przywróć", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { !data.backupWorking.value && data.backupNotice.value?.startsWith("Kopia została przywrócona") == true }
        assertEquals(137, model.state.value.config.tempo.bpm)
        runBlocking { assertEquals("Z kopii", data.dao.allPresets().single().name) }
        compose.activityRule.scenario.recreate()
        assertEquals(137, model.state.value.config.tempo.bpm)
    }

    @Test fun cancelledPickerAndConfirmationPreserveData() {
        importResult(null)
        compose.onNodeWithText("Przywrócić kopię?").assertDoesNotExist()
        importResult("roundtrip.ombak")
        compose.onNodeWithText("Anuluj").performClick()
        assertEquals(91, model.state.value.config.tempo.bpm)
        runBlocking { assertTrue(data.dao.allPresets().isEmpty()) }
    }

    @Test fun invalidDocumentShowsErrorWithoutChangingConfiguration() {
        importResult("invalid.ombak")
        compose.onNodeWithText("Przywróć", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { !data.backupWorking.value && data.backupNotice.value?.contains("pozostają bez zmian") == true }
        assertEquals(91, model.state.value.config.tempo.bpm)
    }

    @Test fun exportWritesContentUriAndCancellationDoesNotOpenDocument() {
        launchResult(Intent.ACTION_CREATE_DOCUMENT, null) { compose.onNodeWithText("Kopia zapasowa").performScrollTo().performClick() }
        launchResult(Intent.ACTION_CREATE_DOCUMENT, "roundtrip.ombak") { compose.onNodeWithText("Kopia zapasowa").performScrollTo().performClick() }
        compose.waitUntil(10000) { !data.backupWorking.value && data.backupNotice.value == "Kopia zapasowa została zapisana." }
        val copy = instrumentation.targetContext.contentResolver.openInputStream(uri("roundtrip.ombak"))!!.use(BackupArchive::read)
        assertEquals(91, ConfigCodec.decode(copy.settings).tempo.bpm)
    }

    @Test fun backupAndStartAreDisabledDuringOperationAndPlaybackReservation() {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        data.operation { entered.complete(Unit); release.await() }
        runBlocking { entered.await() }
        try {
            data.exportBackup { ByteArrayOutputStream() }
            compose.onNodeWithText("Przetwarzanie…").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Start").assertIsNotEnabled()
        } finally { release.complete(Unit); runBlocking { data.awaitWrites() } }
        assertTrue(data.reservePlayback())
        try { compose.onNodeWithText("Przywróć kopię").performScrollTo().assertIsNotEnabled() }
        finally { data.playbackFinished() }
    }
    private fun systemNode(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun clickSystem(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean) {
        instrumentation.uiAutomation.waitForIdle(500, 10000)
        val deadline = android.os.SystemClock.uptimeMillis() + 15000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val target = systemNode(predicate)
            if (target != null && target.isEnabled && target.isVisibleToUser) {
                // Tap the matched row itself: a clickable RecyclerView ancestor may
                // acknowledge ACTION_CLICK without activating the selected document.
                val bounds = android.graphics.Rect()
                target.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    val downTime = android.os.SystemClock.uptimeMillis()
                    fun touch(action: Int): Boolean {
                        val event = android.view.MotionEvent.obtain(downTime,
                            android.os.SystemClock.uptimeMillis(), action,
                            bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        return try { instrumentation.uiAutomation.injectInputEvent(event, true) }
                        finally { event.recycle() }
                    }
                    val down = touch(android.view.MotionEvent.ACTION_DOWN)
                    Thread.sleep(60)
                    val up = touch(android.view.MotionEvent.ACTION_UP)
                    if (down && up) {
                        instrumentation.uiAutomation.waitForIdle(500, 10000)
                        return
                    }
                }
            }
            Thread.sleep(100)
        }
        val tree = StringBuilder()
        systemNode { node ->
            tree.append("\n").append(node.className).append(" ").append(node.viewIdResourceName)
                .append(" text=").append(node.text).append(" desc=").append(node.contentDescription)
            false
        }
        throw AssertionError("System picker control unavailable:$tree")
    }
    private fun selectFixtureRoot() {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun inSystemList(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            var parent = node.parent
            while (parent != null) {
                if (parent.viewIdResourceName?.endsWith("/roots_list") == true) return true
                parent = parent.parent
            }
            return false
        }
        val isFixtureRoot: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean = {
            it.text?.toString() == "OpenMetronome test backup" && inSystemList(it)
        }
        if (systemNode(isFixtureRoot) == null) {
            clickSystem {
                val description = it.contentDescription?.toString()?.lowercase().orEmpty()
                it.viewIdResourceName == "android:id/home" || description.contains("roots") ||
                    description.contains("lokalizac") || description.contains("główne") || description.contains("navigation drawer")
            }
        }
        clickSystem(isFixtureRoot)
    }

    @Test fun realSystemPickerExportsAndImportsOnlyFixtureProvider() {
        try {
            compose.onNodeWithText("Kopia zapasowa").performScrollTo().performClick()
            selectFixtureRoot()
            clickSystem { it.text?.toString()?.lowercase() in listOf("save", "zapisz") }
            compose.waitUntil(15000) { !data.backupWorking.value && data.backupNotice.value == "Kopia zapasowa została zapisana." }
            compose.runOnIdle { model.setConfig(MetronomeConfig(tempo = Tempo(137))) }
            runBlocking { data.awaitWrites() }
            compose.onNodeWithText("Przywróć kopię").performScrollTo().performClick()
            selectFixtureRoot()
            clickSystem { it.text?.toString() == "roundtrip.ombak" }
            // A physical tap returns before DocumentsUI has resumed our Activity.
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("Przywróć", useUnmergedTree = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
            }
            compose.onNodeWithText("Przywróć", useUnmergedTree = true).performClick()
            compose.waitUntil(15000) { !data.backupWorking.value && data.backupNotice.value?.startsWith("Kopia została przywrócona") == true }
            assertEquals(91, model.state.value.config.tempo.bpm)
        } catch (failure: Throwable) {
            val tree = StringBuilder()
            systemNode { node ->
                tree.append("\n").append(node.viewIdResourceName).append(" text=").append(node.text)
                    .append(" desc=").append(node.contentDescription)
                false
            }
            throw AssertionError("Backup notice=${data.backupNotice.value}, working=${data.backupWorking.value}; screen:$tree", failure)
        } finally {
            // Close a system picker if a platform-specific control could not be found.
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true)
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

}
