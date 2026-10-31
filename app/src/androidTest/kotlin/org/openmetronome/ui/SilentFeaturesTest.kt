package org.openmetronome.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.openmetronome.*
import org.openmetronome.R
import org.openmetronome.audio.PlaybackStatus
import org.openmetronome.data.*
import org.openmetronome.domain.model.*

/** Real UI and repositories, but no Start action, hardware audio, or vibration. */
@RunWith(AndroidJUnit4::class)
class SilentFeaturesTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule())
        .around(UnlockedScreenRule()).around(compose)
    private lateinit var model: MetronomeViewModel
    private lateinit var data: AppData

    @Before fun resetTestDataOnly() {
        data = (compose.activity.application as TestApplication).data
        model = ViewModelProvider(compose.activity)[MetronomeViewModel::class.java]
        model.stop()
        compose.waitUntil(10000) { model.state.value.audio.status == PlaybackStatus.STOPPED }
        runBlocking(Dispatchers.IO) {
            data.ready.await()
            data.database.clearAllTables()
            data.samples.load()
        }
        data.error.value = null
        compose.runOnIdle { model.setConfig(MetronomeConfig()) }
    }

    @After fun neverLeavePlaybackRunning() {
        if (::model.isInitialized) {
            model.stop()
            compose.waitUntil(10000) { model.state.value.audio.status == PlaybackStatus.STOPPED }
        }
    }

    @Test fun trainingEditorsRejectInvalidNumbersAndPreserveConfigAcrossRotation() {
        expandTraining()
        compose.onNodeWithContentDescription("Prawdopodobieństwo wyciszenia")
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        toggle("Wyciszanie całych taktów")
        field("Takty słyszalne", "0")
        compose.runOnIdle { assertEquals(2, model.state.value.config.silence.audibleBars) }
        field("Takty słyszalne", "64")
        compose.onNodeWithContentDescription("Zwiększ: Takty słyszalne").assertIsNotEnabled()
        clickWhenStationary(compose.onNodeWithContentDescription("Zmniejsz: Takty słyszalne"))
        compose.waitUntil(10000) { model.state.value.config.silence.audibleBars == 63 }
        field("Takty ciszy", "1")
        toggle("Progresja wyciszeń")
        field("Wzrost o p.p.", "25")
        field("Co ile sekund", "3")
        toggle("Automatyczne tempo")
        field("Tempo początkowe", "300")
        field("Tempo docelowe", "137")
        field("Krok BPM", "17")
        field("Co ile taktów", "2")
        compose.onNodeWithText(Subdivision.TRIPLETS.label).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Akcenty podziałów").performScrollTo().performClick()
        compose.onNodeWithText("Podział 2: zwykły").performScrollTo().performClick()
        compose.onNodeWithText("Podział 2: akcent").performClick()
        val expected = MetronomeConfig(silence = SilenceConfig(100, true, 63, 1),
            progression = SilenceProgression(true, 25, 3), ramp = TempoRamp(true, 300, 137, 17, 2),
            rhythm = RhythmConfig(Subdivision.TRIPLETS, listOf(ClickAccent.NORMAL, ClickAccent.MUTE, ClickAccent.NORMAL)))
        compose.runOnIdle { assertEquals(expected, model.state.value.config) }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            assertEquals(expected, model.state.value.config)
            assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
            assertFalse(model.state.value.config.vibration)
        }
    }

    @Test fun everyMutePercentageIncludingFortyIsSelectableWithoutHardware() {
        expandTraining()
        val slider = compose.onNodeWithContentDescription("Prawdopodobieństwo wyciszenia")
        slider.performScrollTo()
        for (percent in 0..100) {
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(percent.toFloat()) }
            compose.runOnIdle { assertEquals(percent, model.state.value.config.silence.probability) }
        }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(39.999996f) }
        compose.runOnIdle { assertEquals(40, model.state.value.config.silence.probability) }
        compose.onNodeWithContentDescription("Zmniejsz wyciszenia o 1 punkt procentowy").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(39, model.state.value.config.silence.probability) }
        compose.onNodeWithContentDescription("Zwiększ wyciszenia o 1 punkt procentowy").performClick()
        compose.runOnIdle { assertEquals(40, model.state.value.config.silence.probability) }
        assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
    }

    @Test fun lampPatternAndNumericTempoSurviveRecreationWithoutPlayback() {
        compose.onNodeWithContentDescription("Puls 2: zwykły").performScrollTo().assertIsDisplayed().performClick()
        awaitDescription("Puls 2: akcent")
        compose.onNodeWithContentDescription("Puls 2: akcent").performClick()
        awaitDescription("Puls 2: cisza")
        compose.onNodeWithContentDescription("120 BPM").performClick()
        compose.onNodeWithText("BPM (20–300)").performTextReplacement("301")
        compose.onNodeWithText("Zastosuj").assertIsNotEnabled()
        compose.onNodeWithText("Anuluj").performClick()
        compose.runOnIdle { assertEquals(120, model.state.value.config.tempo.bpm) }
        compose.onNodeWithContentDescription("120 BPM").performClick()
        compose.onNodeWithText("BPM (20–300)").performTextReplacement("87")
        compose.onNodeWithText("Zastosuj").performClick()
        awaitDescription("87 BPM")
        // A physical click must wait for the system keyboard, which is not driven by
        // Compose's test clock. Otherwise its closing animation can intercept the tap.
        compose.waitUntil(10000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        compose.onNodeWithContentDescription("Zwiększ tempo o 5 BPM").performScrollTo().assertIsDisplayed().performClick()
        awaitDescription("92 BPM")
        compose.runOnIdle { assertEquals(92, model.state.value.config.tempo.bpm) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Puls 2: cisza").assertIsDisplayed()
        compose.onNodeWithContentDescription("92 BPM").assertIsDisplayed()
        assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
    }

    @Test fun presetsSupportCreateLoadRenameReplaceAndConfirmedDeletion() {
        val config = MetronomeConfig(tempo = Tempo(87), silence = SilenceConfig(50, true, 3, 2),
            rhythm = RhythmConfig(Subdivision.TRIPLETS), sound = SoundConfig(37, ClickSound.WOOD))
        compose.runOnIdle { model.setConfig(config) }
        page("Presety")
        field("Nazwa presetu", "Trening testowy")
        click("Zapisz jako nowy")
        awaitPresets { it.size == 1 }
        compose.runOnIdle { model.setConfig(MetronomeConfig()) }
        click("Wczytaj")
        compose.runOnIdle { assertEquals(config, model.state.value.config) }
        click("Edytuj")
        field("Nazwa presetu", "Nowa nazwa")
        click("Zmień nazwę")
        awaitPresets { it.singleOrNull()?.name == "Nowa nazwa" }
        click("Edytuj")
        val replacement = config.copy(tempo = Tempo(211))
        compose.runOnIdle { model.setConfig(replacement) }
        click("Zastąp bieżącymi ustawieniami")
        awaitPresets { it.singleOrNull()?.config == ConfigCodec.encode(replacement) }
        click("Usuń")
        compose.onNodeWithText("Anuluj").performClick()
        awaitPresets { it.size == 1 }
        click("Usuń")
        compose.onNode(hasText("Usuń") and hasAnyAncestor(isDialog())).performClick()
        awaitPresets { it.isEmpty() }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Nie zapisano jeszcze presetów.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nie zapisano jeszcze presetów.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status) }
    }

    @Test fun soundControlsSurviveRotationWithoutStartingHardware() {
        page("Ustawienia")
        val slider = compose.onNodeWithContentDescription(compose.activity.getString(R.string.app_volume_slider))
        for (percent in listOf(0, 37, 100)) {
            slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(percent.toFloat()) }
            compose.runOnIdle { assertEquals(percent, model.state.value.config.sound.volumePercent) }
        }
        for (sound in ClickSound.visible) {
            compose.onNodeWithContentDescription("Wybierz brzmienie").performScrollTo().performClick()
            val label = compose.activity.getString(sound.labelResource())
            compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
            compose.runOnIdle { assertEquals(sound, model.state.value.config.sound.clickSound) }
        }
        slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(37f) }
        captureUi("sound-controls")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Brzmienie: Długi").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(SoundConfig(37, ClickSound.FULL_HELD), model.state.value.config.sound)
            assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
        }
    }

    @Test fun controlsRemainVisibleAcrossScrollingTabsAndLandscape() {
        val start = compose.onNodeWithText(compose.activity.getString(R.string.start))
        start.assertIsDisplayed()
        captureUi("initial")
        val bounds = start.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("training-toggle").performScrollTo()
        start.assertIsDisplayed()
        assertEquals(bounds, start.fetchSemanticsNode().boundsInRoot)
        for (tab in listOf("Presety", "Statystyki", "Ustawienia", "Metronom")) {
            page(tab)
            start.assertIsDisplayed()
        }
        val orientation = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10000) {
                compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            start.assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            start.assertIsDisplayed()
            captureUi("landscape")
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        }
        compose.runOnIdle { assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status) }
    }

    @Test fun archivedSoundRemainsUsableButIsNotInTheCuratedPicker() {
        page("Ustawienia")
        compose.runOnIdle { model.setConfig(model.state.value.config.copy(sound = SoundConfig(37, ClickSound.CLASSIC))) }
        compose.onNodeWithText("Brzmienie: Archiwalne: Klasyczny").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Wybierz brzmienie").performScrollTo().performClick()
        compose.onNodeWithText("Klasyczny").assertDoesNotExist()
        captureUi("sound-picker")
        compose.onNodeWithText(compose.activity.getString(R.string.sound_close)).performClick()
        compose.runOnIdle {
            assertEquals(ClickSound.CLASSIC, model.state.value.config.sound.clickSound)
            assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
        }
    }

    @Test fun diagnosticsOpensAndClosesWithoutStartingPlayback() {
        page("Ustawienia")
        compose.onNodeWithText("Informacje").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Informacje i diagnostyka").assertIsDisplayed()
        compose.onNodeWithText("Stan audio: STOPPED", substring = true).assertExists()
        compose.onNodeWithText("Kopiuj raport").assertIsDisplayed()
        compose.onNodeWithText("Zamknij").performClick()
        compose.onNodeWithText("Kopiuj raport").assertDoesNotExist()
        compose.runOnIdle { assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status) }
    }

    @Test fun importedSampleCanBeSelectedRenamedAndDeletedWithoutHardware() {
        page("Ustawienia")
        val file = java.io.File(compose.activity.cacheDir, "picker-${java.util.UUID.randomUUID()}.wav")
        val pcm = org.openmetronome.domain.audio.SamplePcm.prepare(
            org.openmetronome.domain.audio.SamplePcm.Audio(48000, DoubleArray(4800) { kotlin.math.sin(2 * Math.PI * 1000 * it / 48000) })).pcm
        file.writeBytes(org.openmetronome.domain.audio.SamplePcm.encodeCanonical(pcm))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }
        val monitor = android.app.Instrumentation.ActivityMonitor(filter,
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, android.content.Intent().setData(android.net.Uri.fromFile(file))), true)
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithText("Importuj sample").performScrollTo().performClick()
            compose.waitUntil(10000) { model.state.value.config.sound.customSampleId != null && !data.sampleWorking.value }
            assertEquals(1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor); file.delete() }
        val id = model.state.value.config.sound.customSampleId!!
        val name = data.samples.items.value.single().name
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Brzmienie: $name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Zmień nazwę sampla $name").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Moje kliknięcie")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(10000) { data.samples.items.value.any { it.name == "Moje kliknięcie" } && !data.sampleWorking.value }
        compose.onNodeWithContentDescription("Usuń sample Moje kliknięcie").performScrollTo().performClick()
        compose.onNodeWithText("Anuluj").performClick()
        compose.runOnIdle { assertEquals(id, model.state.value.config.sound.customSampleId) }
        captureUi("sample-library")
        compose.onNodeWithContentDescription("Usuń sample Moje kliknięcie").performScrollTo().performClick()
        compose.onNode(hasText("Usuń") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10000) { model.state.value.config.sound.customSampleId == null && data.samples.items.value.isEmpty() && !data.sampleWorking.value }
        compose.runOnIdle {
            assertEquals(ClickSound.IMPULSE_FULL, model.state.value.config.sound.clickSound)
            assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
        }
    }

    @Test fun cancelledDocumentPickerDoesNotChangeSettingsOrLibrary() {
        page("Ustawienia")
        val before = model.state.value.config
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }
        val monitor = android.app.Instrumentation.ActivityMonitor(filter,
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithText("Importuj sample").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(1, monitor.hits)
            compose.runOnIdle {
                assertEquals(before, model.state.value.config)
                assertTrue(data.samples.items.value.isEmpty())
                assertFalse(data.sampleWorking.value)
                assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun tapButtonDoesNotAutoplayAndFirstTapAfterNavigationKeepsTempo() {
        compose.onNodeWithText("Tap tempo").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(120, model.state.value.config.tempo.bpm)
            model.setTempo(87)
        }
        page("Presety")
        page("Metronom")
        compose.onNodeWithText("Tap tempo").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(87, model.state.value.config.tempo.bpm)
            assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
        }
    }

    @Test fun syntheticStatisticsAndNavigationSurviveRotationWithoutPlayback() {
        val config = ConfigCodec.encode(MetronomeConfig())
        runBlocking(Dispatchers.IO) {
            data.dao.checkpoint(PracticeSession("fixture", 0, 125000, 125000, true, false, "UTC", config),
                listOf(PracticeDay("fixture", "1970-01-01", 125000)))
        }
        page("Statystyki")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Sesje").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("2 min 5 s")[0].performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Od 1969-12-29").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("7 dni").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Sesje").performScrollTo().assertIsDisplayed()
        page("Metronom")
        compose.runOnIdle { assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status) }
        assertEquals(PracticeTotal(1, 125000), runBlocking { data.dao.total().first() })
    }

    @Test fun dataErrorsAreVisibleAndDismissibleWithoutChangingStoredData() {
        runBlocking(Dispatchers.IO) { data.dao.savePreset(Preset("broken", "Uszkodzony", "version=999", 1, 1)) }
        page("Presety")
        awaitPresets { it.size == 1 }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Wczytaj").fetchSemanticsNodes().isNotEmpty() }
        click("Wczytaj")
        val message = "Nie można odczytać tego presetu. Nie został zmieniony."
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Zamknij komunikat").performClick()
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(MetronomeConfig(), model.state.value.config)
            assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
        }
        assertEquals("version=999", runBlocking { data.dao.presets().first().single().config })
    }

    @Test fun csvExportReportsSuccessAndFailureWithoutChangingSessions() {
        val config = ConfigCodec.encode(MetronomeConfig())
        runBlocking(Dispatchers.IO) {
            data.dao.saveSession(PracticeSession("csv-fixture", 0, 1000, 1000, false, false, "UTC", config))
        }
        val output = java.io.ByteArrayOutputStream()
        data.exportCsv { output }
        runBlocking { kotlinx.coroutines.withTimeout(10000) { data.exporting.first { !it } } }
        assertTrue(output.toString("UTF-8").contains("\"csv-fixture\""))
        assertEquals("Zapisano CSV. Liczba sesji: 1.", data.exportNotice.value)
        data.exportCsv { throw java.io.IOException("isolated fixture") }
        runBlocking { kotlinx.coroutines.withTimeout(10000) { data.exporting.first { !it } } }
        assertTrue(data.exportNotice.value!!.startsWith("Nie udało się zapisać CSV."))
        assertEquals(PracticeTotal(1, 1000), runBlocking { data.dao.total().first() })
        assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
        page("Statystyki")
        compose.onNodeWithText("Eksportuj CSV").performScrollTo().assertIsEnabled()
    }

    @Test fun adaptivePanelsUseAvailableWindowAndExposeTrainingOnTablet() {
        val tags = listOf("layout-phone", "layout-phone-wide", "layout-tablet-portrait", "layout-tablet-landscape")
        val tag = tags.single { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        val viewport = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val layout = InstrumentLayout.choose(viewport.width / density, compose.activity.resources.configuration.screenHeightDp.toFloat())
        assertEquals("layout-${layout.name.lowercase().replace('_', '-')}", tag)
        val instrument = compose.onNodeWithTag("instrument-panel").fetchSemanticsNode().boundsInRoot
        if (layout == InstrumentLayout.TABLET_PORTRAIT) assertTrue(instrument.width > viewport.width * .8f)
        if (layout == InstrumentLayout.TABLET_LANDSCAPE) assertTrue(instrument.width < viewport.width * .7f)
        if (layout.tablet) {
            compose.onNodeWithContentDescription("Prawdopodobieństwo wyciszenia").assertExists()
            compose.onNodeWithTag("training-panel").assertExists()
        }
        compose.onNodeWithText("Start").assertIsDisplayed()
        assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
    }

    @Test fun tempoEditorKeepsDraftThroughKeyboardAndRotation() {
        compose.onNodeWithContentDescription("120 BPM").performScrollTo().performClick()
        compose.onNodeWithText("BPM (20–300)").performClick().performTextReplacement("137")
        val originalOrientation = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10000) {
                compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            compose.onNodeWithText("BPM (20–300)").assertTextContains("137")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("BPM (20–300)").assertTextContains("137")
            compose.runOnIdle { assertEquals(120, model.state.value.config.tempo.bpm) }
            compose.onNodeWithText("Zastosuj").performClick()
            awaitDescription("137 BPM")
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
        assertEquals(PlaybackStatus.STOPPED, model.state.value.audio.status)
    }

    private fun expandTraining() {
        val collapsed = compose.onAllNodesWithText("Trening pulsu").fetchSemanticsNodes().isNotEmpty()
        val toggle = compose.onNodeWithTag("training-toggle").performScrollTo()
        if (collapsed) toggle.performClick()
    }
    private fun captureUi(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        check(compose.activity.application is TestApplication)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(compose.activity.cacheDir, "ui-v012-$name.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun awaitDescription(description: String) {
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size == 1
        }
    }
    private fun toggle(label: String) = compose.onNodeWithContentDescription(label).performScrollTo().performClick()
    private fun field(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
        compose.runOnIdle {
            compose.activity.currentFocus?.let { view ->
                (compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                view.clearFocus()
            }
        }
        // Focus/IME requests can arrive after Compose becomes idle. Keep requesting
        // dismissal while waiting for the platform window to finish resizing.
        compose.waitUntil(10000) {
            compose.activity.runOnUiThread {
                androidx.core.view.WindowCompat.getInsetsController(compose.activity.window,
                    compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            } else {
                val frame = android.graphics.Rect()
                val decor = compose.activity.window.decorView
                decor.getWindowVisibleDisplayFrame(frame)
                decor.rootView.height - frame.bottom < 100 * compose.activity.resources.displayMetrics.density
            }
        }
        compose.waitForIdle()
    }
    private fun clickWhenStationary(node: SemanticsNodeInteraction) {
        node.performScrollTo().assertIsDisplayed()
        var bounds = node.fetchSemanticsNode().boundsInWindow
        var stationarySince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10000) {
            val current = node.fetchSemanticsNode().boundsInWindow
            if (current != bounds) {
                bounds = current
                stationarySince = android.os.SystemClock.uptimeMillis()
            }
            android.os.SystemClock.uptimeMillis() - stationarySince >= 200
        }
        node.performClick()
        compose.waitForIdle()
    }
    private fun click(label: String) = clickWhenStationary(compose.onNodeWithText(label))
    private fun page(label: String) {
        if (label == "Presety") compose.onNodeWithContentDescription("Presety").performClick()
        else compose.onAllNodesWithText(label)[0].performClick()
    }
    private fun awaitPresets(predicate: (List<Preset>) -> Boolean) {
        try {
            compose.waitUntil(10000) { runBlocking { predicate(data.dao.presets().first()) } }
        } catch (failure: AssertionError) {
            throw AssertionError("Stored presets: ${runBlocking { data.dao.presets().first() }}\n${compose.onRoot().printToString()}", failure)
        }
        compose.waitForIdle()
    }
}
