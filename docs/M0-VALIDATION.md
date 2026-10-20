# Weryfikacja M0 — 2026-10-18

## Wynik

Zainstalowano narzędzia w katalogu użytkownika i wykonano pełny build Androida.
**Testy JVM i Lint przechodzą, oba debug APK zostały wygenerowane. Aplikację
zainstalowano i uruchomiono na Samsungu. Pełny odbiór interfejsu pozostaje
niewykonany z powodu zrywanego połączenia USB.**

| Sprawdzenie | Wynik |
| --- | --- |
| Wygenerowanie oficjalnego wrappera Gradle 9.5.0 | OK |
| SHA-256 dystrybucji Gradle i JAR wrappera | Zgodne z oficjalnymi sumami |
| `./gradlew --version` z tymczasowym JDK 21 | OK, Gradle 9.5.0 |
| `./gradlew --no-daemon --offline help` | OK, konfiguracja projektu przez wrapper |
| `sh -n gradlew` | OK |
| XML manifestu i zasobów przez `xmllint --noout` | OK |
| Parsowanie katalogu wersji TOML i odwołań do wersji | OK |
| Parsowanie YAML workflow i sprawdzenie wyzwalaczy/uprawnień | OK |
| Odwołania `R.string` z kodu do zasobów | Wszystkie znalezione zasoby istnieją |
| Testy modeli na JVM | 5 testów, 0 błędów, 0 pominiętych |
| Testy ViewModel na JVM | 6 testów, 0 błędów, 0 pominiętych |
| `:app:testDebugUnitTest` | OK: 11 testów, 0 błędów, 0 pominiętych |
| `:app:lintDebug` | OK: brak błędów, ostrzeżenia opisane poniżej |
| `:app:assembleDebug` | OK: wygenerowano APK aplikacji |
| `:app:assembleDebugAndroidTest` | OK: wygenerowano APK testów Compose |
| Podpis obu APK przez `apksigner verify --verbose` | OK: APK Signature Scheme v2 |
| Instalacja aplikacji na Samsungu | OK: `adb install -r` zakończone `Success` |
| Uruchomienie aplikacji na Samsungu | OK: `am start -W`, `Status: ok`, proces i aktywność potwierdzone |
| Uruchomienie testów Compose na Samsungu | Przebieg przerwany utratą ADB; brak kompletu wyników, testy niezaliczone |
| Testy na Lenovo | Niewykonane |
| Uruchomienie workflow na GitHubie | Niewykonane: repozytorium nie zostało opublikowane |

## Środowisko i sposób testowania

Arch Linux x86_64, Git 2.56.0, ADB z Android Tools 37, zainstalowane reguły
`android-udev`. W środowisku początkowym nie było JDK ani Android SDK;
`JAVA_HOME` i `ANDROID_HOME` nie były ustawione. Nie znaleziono Android Studio
w sprawdzonych standardowych lokalizacjach. Sandbox ogranicza dostęp do USB;
diagnostykę urządzenia i operacje ADB wykonano poza nim.

Początkowo używano tymczasowego JDK i Gradle w `/tmp/openmetronome-m0.zctpSf`.
Po poleceniu użytkownika doinstalowano potrzebne narzędzia bez roota, ponieważ
`sudo` wymaga hasła. Nie zmieniano pakietów systemowych ani plików powłoki.

- Temurin JDK **21.0.12.1+1**: `~/.local/share/openmetronome/jdk-21`.
- Android SDK Command Line Tools **22.0**: `~/Android/Sdk/cmdline-tools/latest`.
- Platforma **API 37.0, rewizja 2**, Build Tools **36.0.0**, platform-tools **37.0.1**.
- Zgody licencyjne wymaganych pakietów SDK zaakceptowano podczas zleconej instalacji.
- Środowisko do nowych terminali: `source scripts/dev-env.sh`.

Sumy archiwów JDK i Command Line Tools sprawdzono przed instalacją. SDK Manager
nie znalazł `platforms;android-37`; lista dostępnych pakietów wskazała poprawną
nazwę **`platforms;android-37.0`**. Poprawiono README i workflow CI.

Źródłowe sumy Gradle:

- [Dystrybucja 9.5.0](https://services.gradle.org/distributions/gradle-9.5.0-bin.zip.sha256):
  `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746`.
- [Wrapper JAR 9.5.0](https://services.gradle.org/distributions/gradle-9.5.0-wrapper.jar.sha256):
  `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.

Standardowa weryfikacja po instalacji SDK:

```bash
source scripts/dev-env.sh
./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Użyto Gradle 9.5.0 i zainstalowanego JDK 21; cache Gradle zachowano w
`/tmp/openmetronome-m0.zctpSf/gradle-home`. Pierwszy pełny build zakończył się
`BUILD SUCCESSFUL` (81 zadań). Po aktualizacji reguły testowej Compose
wykonano ponownie pełną weryfikację od `:app:clean`: **BUILD SUCCESSFUL,
82 zadania**, 11 testów JVM zaliczonych. Wcześniejszy błąd
`SDK location not found` został usunięty przez instalację SDK.

Aktualne raporty: `app/build/reports/tests/testDebugUnitTest/index.html`,
`app/build/test-results/testDebugUnitTest/` oraz
`app/build/reports/lint-results-debug.html`. APK aplikacji znajduje się w
`app/build/outputs/apk/debug/app-debug.apk`, a APK testów w
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

Kompilator wskazał przestarzałą regułę testową Compose. Zmieniono import na
`androidx.compose.ui.test.junit4.v2.createAndroidComposeRule`.

Lint zgłasza dostępność nowszych Gradle, AGP i Coroutines, sugestię dodania
`dataExtractionRules` oraz zbędny kwalifikator `v26` ikony przy minSdk 26.
Wersje pozostają przypięte do zweryfikowanego zestawu. Przed zapisaniem danych
w M5 należy uzupełnić reguły kopii danych; obecnie aplikacja ma `allowBackup=false`
i nie przechowuje danych użytkownika. Ostrzeżenie pakowania
`libandroidx.graphics.path.so` bez usunięcia symboli pochodzi z zależności
Compose i nie blokuje debug APK; nie doinstalowywano NDK.

Przed instalacją SDK, aby sprawdzić dostępną logikę, utworzono osobny projekt w
`/tmp/openmetronome-m0.zctpSf/jvm-verification`. Korzysta on z Kotlin JVM
2.4.21, prawdziwej biblioteki Lifecycle ViewModel 2.11.0 w wariancie JVM,
Coroutines 1.10.2 i JUnit 4.13.2. Kompiluje **bezpośrednio pliki repozytorium**:
modele z `domain.model`, `MetronomeViewModel` oraz wszystkie testy z
`app/src/test/kotlin`. Nie kopiuje ani nie zastępuje implementacji atrapami.

Uruchomienie zadania `test` w tym projekcie zakończyło się `BUILD SUCCESSFUL`.
Raporty JUnit wykazały łącznie **11 testów, 0 niepowodzeń, 0 błędów,
0 pominiętych**. Raporty XML są w tymczasowym katalogu
`jvm-verification/build/test-results/test`, a raport HTML w
`jvm-verification/build/reports/tests/test/index.html`.

To była dodatkowa kontrola przed instalacją SDK. Obecnie te same testy przeszły
także przez standardowe `:app:testDebugUnitTest`. Tymczasowy projekt służył
tylko weryfikacji; aplikacja nadal ma jeden moduł `app`.

## Diagnostyka Samsunga

Potwierdzony model: **SM-A145R**, Android **15 / API 35**, numer ADB
**NUMER_SAMSUNGA**. Telefon był widoczny jako `device` i pozwolił odczytać
właściwości systemu. Pakiet `org.openmetronome` nie był wtedy zainstalowany.

Następnie transport ADB znikał mimo obecności telefonu w USB. Sprawdzono
uprawnienia ACL (użytkownik ma odczyt i zapis), aktywny stan zasilania portu
i `authorized=1`. Wypróbowano ADB Arch 37.0.0p1 oraz oficjalne ADB SDK 37.0.1,
backend natywny i libusb. Log zawiera `connection terminated: read failed`,
a libusb zgłasza **`failed to get serial from device 3-1: LIBUSB_ERROR_IO`**.
To błąd komunikacji USB przed połączeniem z aplikacją, nie dowód błędu jej kodu.

Poproszono o odblokowanie telefonu, sprawdzenie zgody na debugowanie i ponowne
podłączenie. Jeśli problem wraca, należy porównać inny przewód i port USB.
Nie resetowano ustawień ani danych telefonu i nie uruchamiano ADB jako root.

Reset wyłącznie połączenia USB (`usbreset SN:NUMER_SAMSUNGA`) i ponowny start
serwera ADB tymczasowo przywróciły transport. To pozwoliło zainstalować APK:
`Performing Streamed Install` → `Success`. `am start -W` potwierdziło
`Status: ok`, a `pm path org.openmetronome` potwierdziło instalację.
Ponowne zimne uruchomienie aktywności trwało **2168 ms** według `am start`.

Wykonano próbę:

```bash
ANDROID_SERIAL=NUMER_SAMSUNGA ./gradlew :app:connectedDebugAndroidTest
```

Runner rozpoczął `settingsSurviveActivityRecreation`, ale w trakcie przebiegu
urządzenie zniknęło z ADB. Zadanie zakończyło się niepowodzeniem:
`device 'NUMER_SAMSUNGA' not found` oraz
`Test run failed to complete. Expected 3 tests, received 0`.
Raport JUnit zawiera jeden niekompletny wpis z pustym elementem `failure`;
nie stanowi to potwierdzenia błędu asercji ani zaliczenia pozostałych testów.
Raport HTML: `app/build/reports/androidTests/connected/debug/index.html`.

Po ponownym przywróceniu transportu potwierdzono widoczną aktywność
OpenMetronome oraz `mDreamingLockscreen=false`. Zebrany fragment logów UID
aplikacji nie zawierał `FATAL EXCEPTION`; zawierał ostrzeżenie Choreographer
o pominięciu 65 klatek przy zimnym starcie. To ograniczona obserwacja,
nie pomiar wydajności ani dowód stabilności długiej sesji.
Bezpośrednia próba uruchomienia AndroidJUnitRunner oraz pobrania zrzutu ekranu
również napotkała utratę transportu. Zrzut był niekompletny i nie posłużył do
oceny wizualnej. Przed kolejnym testem potrzebne jest stabilne USB i odblokowany ekran.

## Pozostałe sprawdzenia

1. Przywrócić stabilne połączenie ADB z Samsungiem.
2. Ponowić pełny przebieg testów Compose i wizualną kontrolę zainstalowanej aplikacji.
3. Na Samsungu i Lenovo sprawdzić oba motywy, obrót, dużą czcionkę,
   układ w orientacji poziomej i uruchomienie w trybie samolotowym.
4. Potwierdzić działanie workflow po publikacji repozytorium na GitHubie.

Nie sprawdzano precyzji audio: M0 nie zawiera silnika dźwięku.

## Ograniczenie Git

Katalog `.git` jest pusty i tylko do odczytu, a `git status` nie rozpoznaje
repozytorium. Nie inicjalizowano Git, nie tworzono commitów, nie wykonywano
push ani nie usuwano chronionego katalogu. Inicjalizacja repozytorium wymaga
zapisywalnego katalogu metadanych w środowisku użytkownika.
