# Weryfikacja M1 — 2026-10-18/19

## Stan odbioru

Wersja `0.2.0-m1` (versionCode 2). Zaimplementowano odtwarzanie PCM przez
AudioTrack, scheduler, zmianę ustawień na granicach taktów, start/stop,
podstawowy audio focus i pierwszoplanowy cykl życia.

**Kod i kompilacja są zweryfikowane lokalnie; odbiór M1 na urządzeniu nie jest
jeszcze zakończony.** Nie należy utożsamiać gotowego APK ani testów JVM
z potwierdzeniem działania rzeczywistego wyjścia audio.

## Wykonane sprawdzenia

Środowisko: Arch Linux, Temurin JDK 21, Android SDK API 37.0, Build Tools
36.0.0, Gradle Wrapper 9.5.0. Wersje zależności pozostają z M0. Narzędzia
w katalogu użytkownika; nie zmieniano pakietów systemowych.

```bash
source scripts/dev-env.sh
./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Pełny przebieg po poprawce zgodności API: **BUILD SUCCESSFUL**, 31 testów JVM.
Po dodaniu dwóch testów podczas przeglądu ponownie zaliczono **33 testy JVM,
zero niepowodzeń i pominiętych**. Oba APK skompilowane, podpisy v2 zweryfikowane
przez `apksigner`. Lint: brak błędów, pięć istniejących ostrzeżeń
(nowsze AGP, Gradle i Coroutines, dataExtractionRules oraz kwalifikator ikony).
Pełny przebieg przed próbami sprzętowymi: **BUILD SUCCESSFUL**, 81 zadań,
1 min 25 s. Po dodaniu diagnostyki i przebudowie testu sprzętowego ponownie
**BUILD SUCCESSFUL**, 81 zadań, 1 min 54 s; 33 testy JVM i Lint zaliczone.

Pierwszy Lint wykrył `BigInteger.longValueExact`, dostępne na Androidzie od
API 31. Zastąpiono je jawnym sprawdzeniem liczby bitów i `toLong`, zachowując
minSdk 26 oraz ochronę przed przepełnieniem. Nie wyciszano błędu Lint.

Zakres testów:

- Pozycje uderzeń przy 44,1/48 kHz i tempach granicznych oraz niepodzielnych.
- Symulacja 24 godzin przy 137 i 300 BPM: zgodność z dokładnym wzorem,
  ramki przekraczające zakres `Int`, bez narastającego błędu zaokrągleń.
- Akcent, metra i zbiorcza zmiana konfiguracji dopiero na początku taktu.
- Wszystkie całkowite tempa 20–300 BPM rosnąco i malejąco ze zmianami metrum,
  porównane z niezależnym wzorcem w postaci skracanego ułamka.
- PCM niezależne od bloków 1, 64, 257, 997 i 1024 ramek; ciągłość kliknięć.
- Obwiednia, brak clippingu, pozycje początków klików i eksport WAV.
- Zmiana tempa, metrum i akcentu w wyrenderowanym PCM na granicy taktu.
- Częściowe zapisy, błędne wyniki zapisu, Stop podczas blokującego zapisu
  i uzyskiwania focus, odmowa focus, retry, utrata urządzenia, stary callback
  focus oraz zwalnianie zasobów mimo wyjątku.
- Konfiguracja, kontroler, ViewModel i zachowanie ustawień po błędzie.

Raporty lokalne:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/test-results/testDebugUnitTest/`
- `app/build/reports/lint-results-debug.html`
- `app/build/reports/audio/m1-137bpm.wav` — 10 s, mono PCM16, 48 kHz.

APK: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256:
`7bc757642494aef3165585f50928ecb38af3a2312755bfdf3b336fd52acb01f9`.

## Samsung: instalacja i testy M1

Samsung `NUMER_SAMSUNGA`: model SM-A145R, Android 15 / API 35 potwierdzony
w M0. W poprzedniej sesji połączenie USB zrywało się; na wznowieniu pracy
2026-10-19 urządzenie nie jest widoczne ani w ADB, ani na liście USB.
Ponowny start ADB i próba odświeżenia połączenia nie przywróciły urządzenia.
Po ponownym podłączeniu przez użytkownika ADB wykryło telefon jako `device`.

Instalacja obu APK przez `adb install -r`: **Success**, bez odinstalowywania
aplikacji. `am start -W`: **Status: ok**, zimny start 3702 ms.
Bezpośredni AndroidJUnitRunner dla `org.openmetronome.ui.MetronomeScreenTest`:
**OK (5 tests)**, 20,461 s, bez niepowodzeń.

**Powtórka po dodaniu logów diagnostycznych niezaliczona: 5/5 niepowodzeń**
z komunikatem `No compose hierarchies found in the app`. Runner wykonał
wszystkie testy, ale nie widział drzewa Compose. Nie jest to zaliczony
regresyjny przebieg na końcowym APK; wcześniejszy sukces nie zastępuje tej
weryfikacji. W połączeniu z brakiem flagi ekranu w długim teście wymaga to
sprawdzenia rzeczywistej widoczności aktywności i ponowienia testów.

Zaliczono pięć testów `MetronomeScreenTest`: stan początkowy, regulacja
tempa, zachowanie ustawień po odtworzeniu aktywności, ciągłość audio przy
odtworzeniu / zatrzymanie poza pierwszym planem oraz pięć cykli Start/Stop.
Opcjonalny `LongAudioSessionTest` jest pomijany bez argumentu `longAudio=true`.
Uruchomiono go osobno z tym argumentem; obie dotychczasowe próby niezaliczone
(szczegóły poniżej), pełna sesja 30-minutowa niepotwierdzona.
Sposób uruchomienia obu zestawów opisano w README. Nie potwierdzono jeszcze
odsłuchu ani fizycznego pomiaru audio; Lenovo i Galaxy A56 także nietestowane.

Pierwsza próba 30-minutowa **niezaliczona**: po około 23 s stan zmienił się
z PLAYING na STOPPED (`AssertionError`). Log końca sesji: 48 kHz, blok 256,
bufor 4104 ramek, 1 129 224 zapisane ramki, **0 underrunów**. Zerwanie ADB
uniemożliwiło bezpośrednie odebranie wyniku; błąd potwierdzono później w logach
TestRunner. Nie ustalono źródła żądania Stop w tym przebiegu — nie przypisujemy
go automatycznie problemowi USB ani awarii silnika audio.

Przegląd wykazał ryzyko w konstrukcji testu: długa pętla czasu rzeczywistego
działała pod regułą Compose z wirtualnym zegarem, bez synchronizowania UI.
Zmieniono próbę sprzętową na `ActivityScenario` bez zegara Compose oraz dodano
oczekiwanie na faktyczne ustawienie `FLAG_KEEP_SCREEN_ON`. Nie jest to dowód
przyczyny poprzedniego zatrzymania. Dodano logi rozróżniające Stop z UI,
`Activity.onStop`, utratę focus i `AUDIO_BECOMING_NOISY`; błędy długiego testu
są teraz zapisywane także w raporcie cache.

Druga próba, już bez zegara Compose: **niezaliczona na etapie przygotowania**,
`Hardware test condition timed out` przy oczekiwaniu na faktyczne
`FLAG_KEEP_SCREEN_ON`. Dźwięk działał około 10 s, 483 592 zapisane ramki,
0 underrunów, po czym test zatrzymał go w `finally`. Nie zaliczamy tego
jako próby 30-minutowej ani jako potwierdzenia poprawności utrzymywania ekranu.
Późniejszy odczyt policy/power wykazał `showing=false`, `inputRestricted=false`
i `mWakefulness=Awake`; nie dowodzi to stanu ekranu podczas samej próby.
Źródło braku flagi wymaga dalszego wyjaśnienia. Wciąż zanika transport ADB;
reset USB przywraca go tylko na krótko. Poproszono o inny przewód/port.

## Lista odbioru urządzeniowego

1. Instalacja przez `adb install -r`: zaliczona na Samsungu, także po diagnostyce.
2. `MetronomeScreenTest`: pierwszy przebieg zaliczony; końcowa powtórka
   niezaliczona — wyjaśnić brak drzewa Compose i ponowić.
3. Odsłuchać 20, 137 i 300 BPM, metra 2/4–6/8 oraz akcent włączony/wyłączony.
4. Podczas grania zmieniać tempo i metrum: całość ma wejść na granicy taktu;
   obrót nie może przerwać audio. Home i ręczna blokada mają je zatrzymać.
5. Sprawdzić focus/przerwanie przez inną aplikację, odłączenie słuchawek
   i brak samoczynnego wznowienia. Powrót do aplikacji nie uruchamia audio.
6. Wykonać 30-minutowy test: PLAYING przez całą sesję i zero underrunów.
7. Zweryfikować Lenovo bez Google Play Services i pomiar fizycznego audio.

Zero underrunów nie mierzy sprzętowego dryfu zegara, jittera ani opóźnienia
toru audio. Do tych pomiarów potrzebne jest osobne nagranie/loopback.
Nie uruchomiono workflow na GitHubie; lokalnie wykonano jego zadania buildowe.
Nie rozpoczęto M2.
