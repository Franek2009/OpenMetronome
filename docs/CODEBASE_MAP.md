# Mapa kodu OpenMetronome

Mapa wejść do kodu linii 1.1 beta; przed zmianą sprawdź wskazaną implementację.
Nie zastępuje wyników testów ani aktualnego stanu wydania.

| Obszar | Najważniejsze miejsca i granice |
| --- | --- |
| Kompozycja aplikacji | `app/src/main/kotlin/org/openmetronome/OpenMetronomeApplication.kt` tworzy silnik i AppData. Fabryki `diagnostics/EngineDiagnosticsFactory.kt` w source set prod/dev rozdzielają obserwatory. |
| Audio | `domain/audio/BeatScheduler.kt` planuje pozycje w ramkach; `PcmRenderer.kt` renderuje klik, podziały, trening i odliczanie. `audio/PcmAudioEngine.kt` posiada worker, sink, konfigurację i końcowy stan. `SampledClickBank.kt` zawiera wygenerowane pary CC0, dekodowane przed renderowaniem, bez I/O w pętli. Nie zastępować zegara PCM timerem UI. |
| Android i tło | `platform/audio/MetronomeService.kt` zarządza foreground, rezerwacją i finalizacją; `PlaybackClient.kt` wiąże UI z usługą. `AndroidPcmSink.kt` i `AndroidAudioFocusGate.kt` są granicami platformy. |
| UI | `ui/AppScreens.kt`, `MetronomeScreen.kt`, `FullscreenPractice.kt`, `StatisticsPanels.kt`; `CurrentPracticeDate.kt` obserwuje datę tylko dla widocznych Statystyk; anulowanie nie zamyka równolegle kanału tickera. Stan i polecenia przez `MetronomeViewModel.kt` i `audio/MetronomeController.kt`. Pełny ekran nie tworzy nowego silnika. |
| Widgety | `widget/WidgetModel.kt` projektuje stan bez pulsu, `WidgetHost.kt` odświeża RemoteViews i statystyki, `WidgetReceivers.kt` odbiera akcje. Polecenia przez binder istniejącego `MetronomeService`, bez drugiego silnika. [Zakres i ograniczenia](WIDGETS.md). |
| Zapis i odzyskiwanie | `data/AppData.kt` koordynuje operacje, DataStore, import/eksport i blokady. `SessionRecorder.kt` tworzy checkpointy; `SessionWriteQueue.kt` utrwala kolejkę ponowień. Nie zwalniać bezpieczeństwa eksportu przy zaległym zapisie. |
| Historia/statystyki | `data/PracticeDatabase.kt` (Room 3), `PracticeTimeline.kt`, `SessionTimelineCodec.kt`, `StatisticsPreferences.kt`; schematy w `app/schemas/`. Przebieg starych sesji pozostaje nieznany. [Semantyka](STATISTICS-2.md). |
| Dane przenośne | `data/BackupArchive.kt`: v2, import v1 i eksport zgodny v1. `SessionCsv.kt`: eksport analityczny. Zmiana Room nie oznacza automatycznie zmiany archiwum. [Format i zgodność](STATISTICS-2.md). |
| DEV | `app/src/dev/kotlin/org/openmetronome/diagnostics/`: ograniczony bufor, TraceSessionStore, TraceJournal, TraceReports i fabryka. Implementacja magazynu nie należy do PROD. Pełna rejestracja wyłączona w beta. [Zakres i wyniki](V1.1-DEV-INTEGRATION-VALIDATION.md). |
| Warianty/podpis | `app/build.gradle.kts`: prod/dev × debug/release, pakiety i oddzielne klucze. `scripts/package-release.sh` rozpoznaje DEV wyłącznie przez jawne `--dev`, metadane i certyfikat. [Konfiguracja](BUILD-VARIANTS.md). |
| CI/wydanie | `.github/workflows/android.yml`: JVM/Lint/PCM/buildy, ciche API26/35, izolacja i aktualizacje testowe. `release.yml`: podpisanie PROD, opcjonalnie DEV (`include_dev`), zawsze draft; `scripts/create-release-draft.sh` sprawdza sumy i dobiera pliki/status. `dev-qualification.yml`: oddzielna ścisła kwalifikacja nieaktywnego rejestratora. |

## Weryfikacja zależna od zmiany

- Logika: testy JVM w `app/src/test/`; implementacja DEV także `app/src/testDev/`.
- UI, Room, recovery i izolacja: `app/src/androidTest/` oraz `androidTestDev/`,
  zawsze na izolowanych danych. `OpenMetronomeTestRunner` blokuje audio/wibracje.
- PCM offline: `scripts/analyze-click-bank.py`; nigdy nie oznacza fizycznego pomiaru audio.
- Pełna regresja: CI dokładnego HEAD przed scaleniem oraz końcowego main przed tagiem.
- Regresja wydania jawnie wyklucza oznaczony benchmark DEV; nie zalicza go jako PASS.
  Osobne `scripts/qualify-dev-emulator.sh` zachowuje budżet +10% i kończy się błędem przy FAIL.
- Oryginalne podpisane APK: `scripts/verify-upgrade.sh --official` / `--official-beta` / `--official-dev`,
  tylko świeże emulatory, znane hashe/certyfikaty i `install -r`, bez przepodpisywania.

Nie uruchamiać audio/wibracji ani operacji na telefonach bez zgody. Nie czyścić
ani nie odinstalowywać istniejących aplikacji. Manualny odbiór oraz niezmierzony
jitter/latencja są oddzielne od testów automatycznych: [M6](M6-ACCEPTANCE.md).
