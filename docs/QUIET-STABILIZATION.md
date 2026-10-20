# Cicha stabilizacja M2–M5

## Zgoda i stan odbioru

Aktualizacja 0.7.1: po wznowieniu 2026-10-20 obowiązują ponownie przekazane
zasady użytkownika: bez kolejnych testów audio i wibracji. Poprzedni przebieg
trzech krótkich testów Samsunga jest wynikiem historycznym, nie walidacją
końcowej poprawki UI. Domyślny zestaw nadal cichy.
Zakres i wyniki: [0.7.1](V0.7.1-VALIDATION.md). Poniższe zapisy opisują
historię ograniczeń i otwarte, osobne bramki odbioru.

Użytkownik 2026-10-19 zabronił na razie testów z dźwiękiem; sam powiadomi,
kiedy będzie można je wykonać. Do tego czasu nie odtwarzamy na urządzeniu
PCM (również przy zerowej głośności) i nie uruchamiamy wibracji.
Podłączenie USB ani zgoda na dalszą implementację nie stanowią zgody na audio.

Aktualizacja: użytkownik potwierdził podstawowy odsłuch („dźwięk działa”).
Pełny odbiór audio pozostaje otwarty; nie podano warunków, BPM ani sprawdzonych
trybów. Potwierdzenie odsłuchu nie jest zgodą na automatyczne testy audio
lub wibracji. Historyczne krótkie próby AudioTrack nie potwierdzają
stabilności sesji po blokadzie ani rzeczywistej precyzji wyjścia.
Nie publikujemy 1.0. Dalsze kroki: [odbiór M6](M6-ACCEPTANCE.md).

## Zabezpieczenia instrumentacji

- `@RequiresAudio` oraz zewnętrzna `AudioPermissionRule` pomijają test przed
  utworzeniem Activity, jeśli brakuje `allowAudio=true`. Pominięcie nie jest PASS.
- Testowa aplikacja dodatkowo blokuje `AudioEngine.start` przed pozyskaniem
  focus i utworzeniem AudioTrack. Chroni też przed nieoznaczonym testem.
- Wibracje mają niezależną blokadę `allowHaptics=true`. Nie włączamy jej razem
  z audio automatycznie. Flagi są technicznym potwierdzeniem uprzedniej zgody,
  nie zastępują rozmowy z użytkownikiem.
- Ochrona dotyczy aplikacji instrumentacji, nie zwykłej aplikacji uruchomionej
  ręcznie. Nie klikamy jej Start podczas cichej pracy.
- Testy UI wymagają interaktywnego, odblokowanego ekranu. Niespełnienie tego
  warunku zgłasza pominięcie środowiskowe, nie sukces ani błąd funkcji.
- Po sprawdzeniu tego warunku ciche testy UI ustawiają `decorView.keepScreenOn`
  na swoich Activity przez callbacki cyklu życia. W `finally` przywracają
  poprzednie wartości i wyrejestrowują callbacki. Nie wybudzają ani nie
  odblokowują urządzenia i nie zmieniają systemowego timeoutu. Mechanizm
  nie dotyczy testów `@RequiresAudio`, zwłaszcza prób wygaszania ekranu.
- Testowa aplikacja ma bazę w pamięci i osobne preferencje. Czyszczenie fixture
  w `SilentFeaturesTest` jest dozwolone wyłącznie po sprawdzeniu typu TestApplication.

## Cichy przebieg

```bash
source scripts/dev-env.sh
./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Po sprawdzeniu `adb devices -l`, przy odblokowanym ekranie:

```bash
ANDROID_SERIAL=NUMER_SAMSUNGA ./gradlew :app:connectedDebugAndroidTest
```

Bez parametrów audio cały zestaw jest cichy. Sprawdź liczbę pominiętych testów:
trzy krótkie testy audio i dwa długie muszą być pominięte. Zablokowany ekran
pomija dodatkowo testy UI. Testy zabezpieczeń i danych nie wymagają ekranu.
Nie stosuj `pm clear` ani reinstalacji przez odinstalowanie.

Nowe scenariusze obejmują wspólne granice ramp/progresji/ciszy, minutę
wygenerowanego PCM przy 44,1/48 kHz w obu kierunkach rampy, reset treningu
po edycji programu, edycję pól i akcentów UI, CRUD presetów z anulowaniem
usuwania, statystyki na sztucznych danych, obrót oraz bezpieczny błąd presetu.
DataStore sprawdzamy także po zamknięciu i ponownym utworzeniu samego store,
nie tylko repozytorium. Nie jest to test ubicia procesu całej aplikacji.

## Odbiór po przyszłej zgodzie — nie uruchamiać teraz

1. Krótki odsłuch na Samsungu: BPM 20/137/300, akcenty i 6/8, podziały,
   wyciszenia 0/50/100%, cykle taktów i rampy w obu kierunkach. Weryfikacja
   człowieka jest oddzielna od asercji PCM. Wibracje tylko po osobnej zgodzie.
2. Krótkie testy odtwarzania: wielokrotny Start/Stop, obrót, Home, blokada,
   Stop z powiadomienia, focus/noisy, brak automatycznego wznowienia.
3. Sesja 30 minut z wyłączonym ekranem. Przed próbą zapisać model, Android,
   wersję APK, warunki zasilania, trasę audio i zgodę. Nie traktować samego
   wyłączenia ekranu jako dowodu wejścia w Doze.

Wyłącznie po zgodzie na taki długi test:

```bash
ANDROID_SERIAL=NUMER_SAMSUNGA ./gradlew :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=org.openmetronome.ui.LongAudioSessionTest#thirtyMinutesWithScreenOffWithoutUnderruns' \
  -Pandroid.testInstrumentationRunnerArguments.allowAudio=true \
  -Pandroid.testInstrumentationRunnerArguments.longAudio=true
```

Test najpierw sprawdza KEEP_SCREEN_ON w UI, następnie usuwa Activity z pierwszego
planu i wyłącza ekran. Wymaga PLAYING, zera underrunów, rosnącego playback head
i wyłączonego ekranu podczas całego monitorowania. W `finally` zatrzymuje
silnik i wybudza ekran, bez obchodzenia zabezpieczeń blokady. Raport:

```bash
adb -s NUMER_SAMSUNGA shell run-as org.openmetronome cat cache/audio-screen-off-session.txt
```

Dotychczasowy test `#thirtyMinutesWithoutUnderruns` pozostaje opcjonalną
próbą z włączonym ekranem (`cache/m1-long-session.txt`). Nie uruchamiać całej
klasy z flagami audio bez potrzeby: zawiera dwa odrębne testy po 30 minut.

4. Porównać zapis sesji i diagnostykę z obserwacją. Zero underrunów nie mierzy
   fizycznego jitteru, opóźnienia głośnika ani synchronizacji wibracji.
5. Powtórzyć właściwe kontrole na Lenovo bez usług Google, gdy będzie dostępne.

Wyniki bieżące: [raport](M2-M5-VALIDATION.md). Brak zaliczonego odbioru audio
blokuje uznanie stabilizacji sprzętowej za zakończoną, ale nie ciche testy.
