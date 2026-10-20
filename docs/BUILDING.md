# Budowanie i testowanie

Instrukcja dla osób rozwijających aplikację. Gotowy APK będzie w GitHub Releases.

## Wymagania

- Android 8.0 / API 26 lub nowszy na urządzeniu.
- Linux x86_64 i JDK 21 do budowania; Android Studio jest opcjonalne.
- Android SDK Command Line Tools, platforma API 37 i Build Tools 36.0.0.
- Połączenie z internetem podczas pierwszego pobrania narzędzi i zależności.
- ADB oraz debugowanie USB do instalacji i testów na telefonie.

Projekt zawiera Gradle Wrapper: nie trzeba instalować systemowego Gradle ani
osobnego kompilatora Kotlin. `compileSdk`/`targetSdk` 37 nie podnosi minimalnej
wersji systemu powyżej API 26.

Skrypty i JAR wrappera pochodzą z Gradle i zachowują licencję Apache 2.0
(nagłówki skryptów oraz `META-INF/LICENSE` wewnątrz JAR); licencja MIT dotyczy
kodu OpenMetronome.

| Składnik | Wersja |
| --- | --- |
| JDK / poziom JVM | 21 |
| Android Gradle Plugin | 9.3.3 |
| Gradle Wrapper | 9.5.0 |
| Kotlin / Compose Compiler | 2.4.21 |
| Compose BOM | 2026.09.00 |
| Activity Compose | 1.13.0 |
| Lifecycle | 2.11.0 |
| Room | 2.8.5 |
| Preferences DataStore | 1.2.1 |
| KSP | 2.3.12 |

Wersje bibliotek są przypięte w `gradle/libs.versions.toml`. AGP 9 obsługuje
Kotlin bez dodatkowego pluginu w module aplikacji; deklaracja KGP w głównym
skrypcie wybiera wersję kompilatora zgodną z pluginem Compose.

Źródła zgodności: [AGP 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes),
[Kotlin i Gradle](https://kotlinlang.org/docs/gradle-configure-project.html),
[Compose BOM](https://developer.android.com/develop/ui/compose/bom),
[Kotlin w AGP](https://developer.android.com/build/migrate-to-built-in-kotlin).
Tabela Kotlin wymienia AGP do 9.3.1; projekt stosuje poprawki tej samej linii
9.3.3. Pełny build, testy JVM i Lint potwierdziły zgodność tego zestawu;
szczegóły i ograniczenia testów urządzeniowych zawiera raport weryfikacji.

## Przygotowanie Arch Linuxa

Poniższe polecenia uruchom samodzielnie. Nie wymagają Android Studio.

```bash
sudo pacman -S --needed jdk21-openjdk android-tools android-udev unzip curl
```

Polecenie korzysta z istniejącej bazy pakietów. Jeśli system wymaga aktualizacji,
wykonaj zwykłą pełną aktualizację Arch Linuxa; nie odświeżaj samej bazy przez
`pacman -Sy` przed instalacją pojedynczych pakietów.

Pobierz [Android Command Line Tools dla Linuxa](https://developer.android.com/studio#command-line-tools-only).
Sprawdź sumę SHA-256 podaną na tej stronie. Rozpakuj archiwum tak, aby istniał
plik `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager` — bez dodatkowego
zagnieżdżenia `latest/cmdline-tools/bin`.

W terminalu używanym do budowania ustaw:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

java -version
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"
```

Przeczytaj i zaakceptuj wymagane licencje SDK. Pakiet API 37 jest publikowany
jako `platforms;android-37.0`, a nie `platforms;android-37`. Pakiet `platform-tools`
w SDK udostępnia ADB dla zadań urządzeniowych Gradle; w terminalu można nadal
używać ADB z pakietu Arch `android-tools`.
Nie ustawiaj sprzecznych `ANDROID_HOME` i `ANDROID_SDK_ROOT` — druga zmienna
jest przestarzała i nie jest wymagana. Jeśli chcesz, zapisz eksporty w swojej
konfiguracji powłoki. Nie commituj lokalnych ścieżek SDK.

### Środowisko lokalne bez roota

Na komputerze projektu zainstalowano Temurin JDK 21 w
`~/.local/share/openmetronome/jdk-21` i SDK w `~/Android/Sdk`, bez modyfikowania
pakietów systemowych ani konfiguracji powłoki. Aby korzystać z tych narzędzi
w nowym terminalu, w katalogu projektu wykonaj:

```bash
source scripts/dev-env.sh
```

Skrypt respektuje jawne `JAVA_HOME` i `ANDROID_HOME`; przy braku lokalnego
JDK sprawdza również systemowe `/usr/lib/jvm/java-21-openjdk`. Nie instaluje
narzędzi i nie jest potrzebny w CI, gdzie zmienne ustawia workflow.

## Budowanie i testy

Powtarzalna cicha weryfikacja (bez modyfikowania konfiguracji systemu):

```bash
bash scripts/verify.sh
# Po pobraniu zależności można dodać --offline.
bash scripts/verify.sh --offline --device NUMER_SAMSUNGA
```

Skrypt korzysta z ignorowanego `.tooling/gradle-home`, jeśli nie podasz
`GRADLE_USER_HOME`, oraz respektuje `JAVA_HOME` i `ANDROID_HOME`. Pierwsza
kompilacja może pobrać zależności. Cache może zajmować kilka GB; nie jest źródłem
projektu i nie jest commitowany. Skrypt nie usuwa istniejących plików cache.
Bez `--device` wykonuje JVM, Lint i oba APK. Z urządzeniem instaluje przez
`install -r` (bez odinstalowania i czyszczenia danych) i ustawia jawnie
`allowAudio=false`, `allowHaptics=false`. Nie wybudza ani nie odblokowuje ekranu.
Raporty trafiają do `app/build/reports/verification-*/`.
Kod wyjścia 0 oznacza zaliczenie żądanego zestawu z dopuszczonymi pominięciami
audio, 1 błąd, a 2 niepełną weryfikację (np. pominięte UI) lub błędne opcje.
Nie wolno traktować pominięć jako zaliczeń.

Parser raportów sprawdzisz bez SDK i telefonu:

```bash
bash scripts/test-verification-parser.sh
```

Z katalogu projektu:

```bash
./gradlew --version
./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Wrapper weryfikuje SHA-256 dystrybucji Gradle. Pierwsze uruchomienie pobiera
Gradle oraz zależności; kolejne kompilacje mogą korzystać z cache. Tryb
`--offline` działa dopiero po pobraniu wszystkich potrzebnych artefaktów.

- APK aplikacji: `app/build/outputs/apk/debug/app-debug.apk`.
- APK testów: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- Raport testów JVM: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Raport Lint: `app/build/reports/lint-results-debug.html`.

Testy JVM obejmują modele, scheduler (również symulację 24 godzin), renderer PCM,
silnik na atrapach urządzenia, kontroler i ViewModel. Nie wymagają emulatora,
ale standardowe zadania modułu Android wymagają zainstalowanego SDK.
Testy urządzeniowe są domyślnie **ciche**. Testy odtwarzania są pomijane,
a aplikacja testowa blokuje audio i wibracje niezależnie od oznaczeń testów.
Testy Compose wymagają odblokowanego ekranu i zaakceptowanego debugowania USB:

```bash
ANDROID_SERIAL=NUMER_URZADZENIA ./gradlew :app:connectedDebugAndroidTest
```

Testy generują 10-sekundowy WAV 137 BPM w `app/build/reports/audio/m1-137bpm.wav`.
Porównanie PCM nie mierzy rzeczywistego wyjścia telefonu. **Użytkownik potwierdził
podstawowy odsłuch: dźwięk działa. Pełny odbiór sprzętowy pozostaje otwarty.**
Automatyczne testy audio i wibracji nadal wymagają osobnej zgody; potwierdzenie
odsłuchu ani samo podłączenie telefonu jej nie zastępują.

Opcjonalny test 30-minutowy jest domyślnie pomijany. Poniższe polecenie jest
wyłącznie instrukcją na później, po nowej, wyraźnej zgodzie na odtwarzanie:

```bash
ANDROID_SERIAL=NUMER_URZADZENIA ./gradlew :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=org.openmetronome.ui.LongAudioSessionTest#thirtyMinutesWithScreenOffWithoutUnderruns' \
  -Pandroid.testInstrumentationRunnerArguments.allowAudio=true \
  -Pandroid.testInstrumentationRunnerArguments.longAudio=true
```

Test wyłącza ekran i kontroluje PLAYING, brak underrunów oraz postęp odtwarzania.
Raport zapisuje w cache jako `audio-screen-off-session.txt`; można go odczytać
przez `adb shell run-as org.openmetronome cat cache/audio-screen-off-session.txt`.
Osobny test `#thirtyMinutesWithoutUnderruns` utrzymuje ekran włączony.
Procedura, niezależna zgoda na wibracje i bramki odbioru:
[cicha stabilizacja](QUIET-STABILIZATION.md).

Testy zapisu bez uruchamiania audio ani ekranu aplikacji:

```bash
ANDROID_SERIAL=NUMER_URZADZENIA ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.openmetronome.data.StorageTest
```

Testy Room korzystają z bazy w pamięci, a DataStore z oddzielnego pliku cache;
nie nadpisują ustawień ani bazy użytkownika. Schemat Room jest wersjonowany
w `app/schemas/`; nie stosujemy `fallbackToDestructiveMigration`.
Cała instrumentacja (również UI/audio) korzysta z `OpenMetronomeTestRunner`
i izolowanej aplikacji testowej. Przy ręcznym `am instrument` używaj komponentu
`org.openmetronome.test/org.openmetronome.OpenMetronomeTestRunner`.

Zwykłe GitHub Actions wykonuje testy JVM, skryptów, Lint debug/release oraz
kompilację debug, instrumentacji i niepodpisanego release. Debug APK i raporty
są zachowywane przez 14 dni. Od 0.9 na gałęzi main workflow uruchamia także
pełny cichy zestaw na świeżym emulatorze API 35, z osobnym raportem pominięć.
Nie uruchamia audio i nie korzysta z fizycznych urządzeń.
Osobny ręczny proces wydawniczy podpisuje APK i wykonuje cichą próbę na świeżym
emulatorze przed utworzeniem draftu. [Wydawanie](RELEASING.md).

## Instalacja na Samsungu

1. Włącz Opcje programisty i Debugowanie USB na telefonie.
2. Podłącz telefon przewodem obsługującym dane i zaakceptuj klucz RSA komputera.
3. Odczytaj numer urządzenia; stan powinien być `device`, nie `unauthorized`:

```bash
adb devices -l
adb -s NUMER_SAMSUNGA install -r app/build/outputs/apk/debug/app-debug.apk
adb -s NUMER_SAMSUNGA shell am start -n org.openmetronome/.MainActivity
```

Zastąp `NUMER_SAMSUNGA` numerem z pierwszego polecenia. `-r` aktualizuje
istniejącą aplikację przy zgodnym podpisie; nie odinstalowuj aplikacji w celu
obejścia niezgodności podpisu bez zabezpieczenia danych. Debug APK nie jest
wydaniem produkcyjnym. Lenovo z LineageOS instaluje się w ten sam sposób,
używając jego numeru urządzenia. Telefon nie potrzebuje dostępu do internetu.

Sprawdzany telefon: **Samsung SM-A145R**, Android 15 / API 35.
Numer urządzenia odczytaj lokalnie przez `adb devices -l`; nie publikuj go.

Gdy ADB nie wykrywa telefonu, sprawdź przewód, zgodę na debugowanie i reguły
`android-udev`. Nie uruchamiaj rutynowo ADB jako root.

## Struktura i dalsza praca

```text
app/src/main/kotlin/org/openmetronome/
  domain/model/        # Kotlin bez Androida: tempo, metrum, konfiguracja
  domain/audio/        # Scheduler w ramkach, generator klików i renderer PCM
  audio/               # Kontrakty, worker audio i kontroler bez Androida
  platform/audio/      # AudioTrack i audio focus
  data/                # Kodek, DataStore, Room i rozliczanie czasu sesji
  ui/                  # ViewModel, ekran Compose i motyw
app/src/test/          # JUnit na JVM
app/src/androidTest/   # Testy Compose na urządzeniu
gradle/               # Wrapper i katalog wersji
docs/                 # Architektura, roadmapa i wyniki weryfikacji
.github/workflows/    # CI
```

Zobacz [architekturę](ARCHITECTURE.md), [roadmapę](ROADMAP.md)
i [stan weryfikacji M2–M5](M2-M5-VALIDATION.md).

Rozpoczynamy przygotowanie [odbioru sprzętowego M6](M6-ACCEPTANCE.md),
bez deklaracji ukończenia stabilizacji. Tap tempo, głośność i wybór barwy
obejmuje wersja 0.6. Pomiary fizycznego audio i wydanie produkcyjne
pozostają otwarte, podobnie jak odbiór na Lenovo.
Proces wydawniczy tworzy draft, nie publikuje automatycznie. [Wydawanie](RELEASING.md).
