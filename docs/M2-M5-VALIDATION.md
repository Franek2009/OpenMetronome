# Pakiet M2–M5 — weryfikacja 2026-10-19

Aktualizacja: wersja 0.6.0-dev dodaje tap tempo, własną głośność i trzy barwy.
Zaliczono 75 JVM i 16 cichych testów Samsunga; 4 audio pominięte.
Szczegóły i ograniczenia: [raport 0.6](V0.6-VALIDATION.md).
Poniższe przebiegi pozostają historią wersji 0.5. Test 30-minutowy
użytkownik odłożył do własnego użytkowania — nie jest zaliczony.

## Zakres

Wersja rozwojowa `0.5.0-dev`, versionCode 3. Kod obejmuje wyciszenia losowe
i taktowe, usługę mediaPlayback, sygnalizację pulsu, progresję ciszy, rampy,
podziały i akcenty, DataStore, presety Room oraz lokalne statystyki.

Nie jest to wydanie 1.0 ani potwierdzenie pełnego odbioru sprzętowego.
Nie wykonywano nowych operacji na telefonie, odtwarzania ani wibracji podczas
nieobecności użytkownika. Nie zmieniano pakietów systemowych.

## Najnowszy wynik M6 — oba krótkie testy audio zaliczone

Po ręcznym odblokowaniu Samsunga SM-A145R `NUMER_SAMSUNGA` wykonano dwa
uzgodnione testy z `allowAudio=true`, `allowHaptics=false` i jawnym wyborem
dwóch metod. **OK (2 tests), 9,311 s, 2 zaliczone, zero błędów i pominięć.**

- `playbackSurvivesRecreationAndLeavingForeground`: PLAYING po Start,
  KEEP_SCREEN_ON ustawione, stan zachowany po odtworzeniu Activity i krótkim
  przejściu do CREATED (tło), powrót do RESUMED i poprawny Stop do STOPPED.
- `repeatedStartStopDoesNotLeaveAudioPlaying`: 5 kolejnych cykli Start/Stop,
  każdy przechodzi przez PLAYING i kończy jako STOPPED; KEEP_SCREEN_ON
  zwolnione po końcowym zatrzymaniu.

Odtwarzanie uruchomiono w ramach wyraźnej zgody użytkownika. Wibracje były
zablokowane, długich testów nie uruchamiano. Testy pracowały na izolowanych
danych. Końcowy odczyt `dumpsys activity services org.openmetronome` zwrócił
`(nothing)`, bez pozostawionej usługi. Log bieżącego przebiegu:
`app/build/reports/audio-short-device.txt`.

Nie zmieniano kodu, APK ani ustawień produkcyjnych; aktualizacja dokumentacji
nie wymagała ponownej kompilacji. Wcześniejsze 59 JVM i 14 cichych testów
pozostają osobnymi zaliczonymi przebiegami. Krótki stan CREATED nie zastępuje
testu Home/blokady przez dłuższy czas. Odsłuch wszystkich trybów, focus,
powiadomienie Stop, próba 30-minutowa, pomiary fizyczne i Lenovo nadal otwarte.
Brak deklaracji ukończenia M6 i gotowości 1.0.

## Wcześniejsza próba M6 — zgoda udzielona, ekran zablokowany

Użytkownik wyraźnie zgodził się na dwa krótkie testy z dźwiękiem, bez
wibracji i bez testu 30-minutowego. ADB wykrył Samsung SM-A145R
`NUMER_SAMSUNGA`; odczyt blokady zwrócił `showing=true`.
Po zwykłym wybudzeniu i `wm dismiss-keyguard` wywołano wyłącznie metody
`playbackSurvivesRecreationAndLeavingForeground` oraz
`repeatedStartStopDoesNotLeaveAudioPlaying`, z `allowAudio=true`
i `allowHaptics=false`.

**Wynik: 0 wykonanych, 2 pominięte z przyczyn środowiskowych**, 0,17 s.
Oba przypadki zakończyły się założeniem `screen must be awake and unlocked`
i kodem -4 przed uruchomieniem Activity. `OK (2 tests)` nie oznacza zaliczenia.
Nie uruchomiono dźwięku ani wibracji, nie zmieniano kodu ani APK.
Log zapisywano do `app/build/reports/audio-short-device.txt`; obecnie plik
zawiera wynik późniejszej, zaliczonej powtórki opisanej powyżej.

Do kontynuacji potrzebne jest ręczne odblokowanie ekranu; zgoda na dokładnie
te dwa krótkie testy już obowiązuje. Długi test, inne scenariusze audio
i wibracje nadal nie są autoryzowane. Wcześniejsze wyniki 59 JVM i 14 cichych
testów pozostają odrębnymi zaliczonymi przebiegami.

## Wcześniejsza aktualizacja odbioru — potwierdzenie użytkownika

Użytkownik zgłosił: **„Przetestowałem, dźwięk działa”**. Podstawowy odsłuch
potwierdzony przez użytkownika; brak szczegółów trasy audio, BPM i trybów.
Nie przypisujemy temu potwierdzeniu wyniku testów automatycznych, pomiaru
precyzji ani zaliczenia działania w tle. Pełny odbiór sprzętowy pozostaje otwarty.

Zatwierdzono przygotowanie odbioru M6: [checklista](M6-ACCEPTANCE.md).
Zaktualizowano wyłącznie dokumentację oraz instrukcje repozytorium.
Nie zmieniono kodu, zależności, wersji ani APK. Nie uruchamiano nowych testów,
nie wykonywano operacji na telefonie i nie odtwarzano audio ani wibracji.
Wyniki 59 JVM i 14 cichych testów poniżej to wcześniejsze wyniki, nie nowy przebieg.
Automatyczne audio oraz wibracje nadal wymagają osobnej zgody.

## Ostatni wynik automatyczny — pełny cichy zestaw zaliczony

Po ponownym podłączeniu i ręcznym odblokowaniu Samsunga SM-A145R
(`NUMER_SAMSUNGA`, API 35) uruchomiono cały zestaw z `allowAudio=false`
oraz `allowHaptics=false`, bez zmiany kodu i bez ponownej instalacji APK.

**Wynik: 34,429 s, 14 testów zaliczonych, 4 testy audio pominięte,
zero niepowodzeń, zero pominięć z powodu środowiska.** Runner wypisał
`OK (18 tests)`; czterech testów audio nie zaliczamy jako wykonanych.

- HardwareSafetyTest: 2/2 — blokady startu audio i wibracji.
- StorageTest: 5/5 — presety, checkpointy, krótkie sesje, DataStore po
  ponownym otwarciu i zachowanie nieobsługiwanych danych po błędzie.
- MetronomeScreenTest: 3/3 ciche — stan początkowy, regulacja BPM,
  ustawienia po odtworzeniu Activity.
- SilentFeaturesTest: 4/4 — edytory treningu i obrót, tworzenie/wczytanie/
  zmiana nazwy/zastąpienie/usuwanie presetu z anulowaniem, statystyki na
  sztucznych danych i nawigacja po obrocie, bezpieczny błąd uszkodzonego presetu.
- LongAudioSessionTest: oba testy pominięte; dwa testy odtwarzania
  MetronomeScreenTest również pominięte zgodnie z ograniczeniem użytkownika.

Testy używały izolowanych magazynów, bez kasowania produkcyjnych presetów,
historii i ustawień. Nie uruchamiano odtwarzania ani wibracji. Odbiór audio,
rzeczywiste odtwarzanie w tle, sesja 30-minutowa i Lenovo nadal są otwarte.
Zaliczenie cichego UI nie oznacza zaliczenia audio/UI ani zakończenia M6.

## Wcześniejsza próba — blokada ekranu i zanik połączenia

Samsung SM-A145R, `NUMER_SAMSUNGA`, API 35. Oba aktualne APK zainstalowano
przez `adb install -r`: **Success**, bez odinstalowania i kasowania danych.
Odczyt pakietu potwierdził `0.5.0-dev`, versionCode 3, minSdk 26, targetSdk 37.
Zgoda POST_NOTIFICATIONS pozostaje niewłączona; nie zmieniano jej.

Uruchomiono cały zestaw, jawnie zabraniając audio i wibracji:

```bash
adb -s NUMER_SAMSUNGA shell am instrument -w -r \
  -e allowAudio false -e allowHaptics false \
  org.openmetronome.test/org.openmetronome.OpenMetronomeTestRunner
```

Dwa przebiegi, odpowiednio 1,148 s i 0,813 s, dały ten sam podział:

| Wynik | Liczba | Zakres |
| --- | ---: | --- |
| Zaliczone | 7 | 5 StorageTest oraz 2 HardwareSafetyTest |
| Pominięte zgodnie z zakazem audio | 4 | 2 krótkie i 2 długie testy odtwarzania |
| Pominięte z powodu blokady ekranu | 7 | 3 podstawowe testy UI i 4 SilentFeaturesTest |
| Niepowodzenia | 0 | Nie oznacza to zaliczenia pominiętego UI |

Runner wypisał `OK (18 tests)`, ale obejmuje to również założenia/pominięcia
(`INSTRUMENTATION_STATUS_CODE: -4`). **Nie zaliczamy wszystkich 18 testów.**
Sprzętowe bezpieczniki sprawdzono na telefonie: próba startu bez opt-in jest
odrzucana przed uruchomieniem silnika, a próba wibracji przed wywołaniem sprzętu.
Żadnego dźwięku ani wibracji nie uruchomiono.

Po zwykłym wybudzeniu i `wm dismiss-keyguard` blokada początkowo nadal była
aktywna. Późniejszy odczyt potwierdził `showing=false`, ale połączenie zerwało
się przed ponowną próbą UI: `device not found`, następnie pusta lista ADB.
Restart serwera nie pomógł; ostatnie `lsusb` nie zawierało już Samsunga.
Nie ustalono przyczyny odłączenia; nie resetowano telefonu ani USB.

Powtórna lokalna weryfikacja: **BUILD SUCCESSFUL, 16 s**, 85 zadań
(1 wykonane, 84 UP-TO-DATE). Zestaw 59 JVM, Lint i oba APK aktualne;
to przebieg przyrostowy, nie ponowne wykonanie wszystkich testów JVM.
Pozostały 4 dotychczasowe ostrzeżenia Lint, bez błędów.

Następny krok bez dźwięku: stabilnie podłączony i odblokowany Samsung,
powtórka pełnej instrumentacji. Do zaliczenia pozostało **7 cichych testów UI**.
Odsłuch, testy audio i wibracji nadal czekają na osobną zgodę.

## Wcześniejsza aktualizacja — cicha stabilizacja 2026-10-19

**Aktualne ograniczenie:** bez dźwięku i wibracji do nowej, wyraźnej zgody
użytkownika. Samo podłączenie urządzenia nie stanowi zgody. Odsłuch i pełny
odbiór audio na żywo pozostają niewykonane; „raczej działa” nie jest wynikiem.
Procedura i przyszłe bramki: [cicha stabilizacja](QUIET-STABILIZATION.md).

Wdrożono:

- `@RequiresAudio` i regułę zewnętrzną pomijającą test przed fixture Activity.
  Audio wymaga `allowAudio=true`, a długi test dodatkowo `longAudio=true`.
- Niezależny bezpiecznik w testowej aplikacji przed `AudioEngine.start`, więc
  również nieoznaczony test nie pozyska focus ani nie utworzy AudioTrack.
  Wibracje wymagają osobnego `allowHaptics=true`. Zwykła aplikacja zachowuje
  dotychczasowe zachowanie; to zabezpieczenia środowiska instrumentacji.
- Kontrolę odblokowania ekranu przed testami UI. Brak warunku środowiskowego
  oznacza pominięcie z przyczyną, nie zaliczenie ani automatyczne wybudzanie.
- 6 nowych testów JVM: wspólne granice ramp/progresji/ciszy, reset programu,
  edycje bez resetu treningu, minutę PCM triol porównaną próbka po próbce przy
  44,1/48 kHz, pełną konfigurację po ręcznym BPM i rozróżnienie konfiguracji
  efektywnej od oczekujących zmian użytkownika.
- 4 nowe ciche testy UI: pola treningu i obrót; pełny CRUD presetów z anulowaniem
  usuwania; sztuczne statystyki/nawigacja/obrót; bezpieczny błąd uszkodzonego
  presetu. Dodano etykiety dostępności suwaków, przełączników i przycisków pól.
- 2 nowe testy DataStore: zamknięcie i ponowne otwarcie samego store oraz
  zachowanie nieobsługiwanego payloadu po błędzie odczytu. To nie jest test
  przerwania procesu całej aplikacji.
- 2 testy samych blokad sprzętu. Osobny test 30-minutowy wyłącza ekran,
  wymaga postępu playback head, PLAYING i zera underrunów; stary test
  z włączonym ekranem pozostaje odrębny. **Żadnego z nich nie uruchamiano.**

Wyniki bieżącego przebiegu:

- **BUILD SUCCESSFUL**, końcowy przebieg 45 s, 85 zadań (6 wykonanych).
- **59 testów JVM, zero niepowodzeń i pominięć**. Pierwszy przebieg rozszerzenia
  miał 56 zaliczonych testów; końcowy obejmuje także trzy dodatkowe regresje.
- Lint: **0 błędów, 4 dotychczasowe ostrzeżenia**.
- APK aplikacji i instrumentacji zbudowane; oba podpisy v2 sprawdzone
  `apksigner verify --verbose`.
- **Nowych testów urządzeniowych nie wykonano.** ADB początkowo widział
  `NUMER_SAMSUNGA` jako `device`, następnie odczyt stanu telefonu nie powiódł się
  i ADB zwrócił `device not found`. Restart serwera ADB nie przywrócił
  połączenia. Samsung był jeszcze widoczny w jednym odczycie `lsusb`;
  nie ustalono przyczyny zaniku ADB. Nie resetowano USB ani telefonu.
- Nie instalowano nowych APK na telefonie, nie uruchamiano instrumentacji,
  rzeczywistego odtwarzania ani wibracji. Wcześniejsze wyniki Samsunga poniżej
  są historyczne i nie stanowią zaliczenia nowych przypadków.

Aktualne APK (nadal `0.5.0-dev`, versionCode 3), SHA-256:

```text
4f9c9a825d0d34ddaac9f3a48a23ed646f21c1c378fd25e264a12302dd93f032  app-debug.apk
5c16a57e269a5161c68cb7f73579c9dff50dfb7447461f7a066c7b8bbc0b0fbe  app-debug-androidTest.apk
```

Przy odblokowanym urządzeniu oczekiwany domyślny zestaw obejmuje 18 testów:
14 cichych do wykonania i 4 audio do pominięcia. To **oczekiwanie**, nie wynik
uruchomienia. Testy UI mogą zostać dodatkowo pominięte przy zablokowanym ekranie.
Następny bezpieczny krok: stabilne ADB i pełna cicha instrumentacja, bez flag
audio. Odbiór dźwięku i M6 pozostają otwarte.

## Wcześniejsze wyniki lokalne

- M2: pełna weryfikacja — BUILD SUCCESSFUL, 36 testów JVM, Lint i oba APK.
- M3/M4: pełna wspólna weryfikacja — BUILD SUCCESSFUL, 40 testów JVM,
  Lint oraz oba APK.
- M5: pierwszy pełny przebieg BUILD SUCCESSFUL, 45 testów JVM, Lint i oba APK.
- Po przeglądzie: **BUILD SUCCESSFUL, 53 testy JVM, zero niepowodzeń,
  zero pominiętych**, Lint bez błędów, APK aplikacji i testów zbudowane.
  Ostatni przebieg: 85 zadań, 1 min 3 s. Podpisy obu APK zweryfikowane
  przez `apksigner` (APK Signature Scheme v2).
- KSP wygenerował DAO i schemat Room 1 w
  `app/schemas/org.openmetronome.data.PracticeDatabase/1.json`.

APK wcześniejszego przebiegu (aktualne sumy powyżej), SHA-256:
`079fc090e8e36cec67bf6e5ed127a200f28c04df0983b3ab9d0b8b2cd8436ae0`.

Lint pozostawia cztery ostrzeżenia: nowsze wersje Gradle/AGP/Coroutines
i kwalifikator ikony v26. Ostrzeżenie wakelock poprawiono: timeout 120 s,
odnawianie co 60 s przez aktualizacje stanu aktywnej sesji, jawne zwalnianie
po zatrzymaniu i zniszczeniu usługi. Nie jest to zegar audio. Zachowanie
w długim teście i zużycie energii nie zostały zmierzone. Nie wyciszano reguł.

Polecenie:

```bash
source scripts/dev-env.sh
./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Przypięto Room 2.8.5, Preferences DataStore 1.2.1 i KSP 2.3.12.
Pozostały Kotlin 2.4.21, AGP 9.3.3 i Gradle 9.5.0. Nie podnoszono minSdk 26.
Projekt nie ma kodu C++ ani własnego NDK; natywne biblioteki dostarczane przez
Jetpack w AAR nie wymagają instalowania NDK do tego buildu.

W jednym przebiegu M3 Lint przerwał wewnętrzny błąd Kotlin UAST podczas
równoczesnej edycji źródła (`pointer already disposed`). Kolejny pełny
przebieg bez edycji w czasie analizy przeszedł; nie wyłączano reguł ani
`abortOnError`. Poprawiono też kolizję nazwy kontrolera w MediaSession.

## Co sprawdzają testy

- Dotychczasowe modele, kontroler, ViewModel, awarie/krótkie zapisy audio.
- Dokładny harmonogram 24 h przy 44,1/48 kHz, wszystkie BPM 20–300 w obu
  kierunkach, zmiany metrum i konfiguracji na granicach taktów.
- Wyciszenia 0%, 50%, 100%, deterministyczne ziarno, cykle taktów i ich suma.
- Brak zależności PCM od bloków, również dla triol, podziałów i ciszy.
- Rampy rosnące/malejące, osiąganie celu bez przekroczenia, progresja ciszy
  liczona na osi audio i ograniczona do 100%.
- Pełny round-trip konfiguracji, odrzucanie nieznanej wersji i błędnych wartości.
- Rozliczanie czasu przez północ oraz rzeczywiste 23/25-godzinne dni zmiany czasu.
- Przepełnienie 32-bitowego licznika AudioTrack, odróżnienie ramek odtworzonych
  od zapisanych, unieważnianie opóźnionego Start po Stop, retry błędu platformy
  oraz wyłączenie rampy przez ręczne BPM.

Przegląd poprawił także anulowanie Start w czasie wczytywania ustawień,
serializację ostatniego checkpointu przed zamknięciem usługi, ochronę zapisu
ustawień przed zamknięciem ekranu i identyfikowanie bardzo krótkich sesji
bez podwójnego doliczania przy kolejnych aktualizacjach stanu.
Każdy renderer otrzymuje własny generator losowości; testy wstrzykują ziarna.

Wygenerowany WAV nadal znajduje się w `app/build/reports/audio/m1-137bpm.wav`.
Testy PCM nie mierzą fizycznego wyjścia audio.

## Samsung — odbiór po ponownym podłączeniu

Samsung SM-A145R (`NUMER_SAMSUNGA`): instalacja APK aplikacji i testów przez
`adb install -r` zakończona `Success`, bez usuwania danych.

`org.openmetronome.data.StorageTest`: **OK (3 tests)**, 0,765 s. Potwierdzono
Room/checkpointy/odzyskiwanie sesji, DataStore i jednokrotny zapis bardzo
krótkich sesji. Testy używają odizolowanych danych, bez audio i wibracji.

Pierwszy wspólny przebieg trzech cichych testów UI: obrót zaliczony, dwa
testy BPM niezaliczone. Te same dwa testy uruchomione osobno przechodzą
(3,121 s i 3,497 s). Przyczyna w przygotowaniu testów: konfiguracja 87 BPM
z testu obrotu pozostawała w testowym DataStore dla kolejnych przypadków.
Dodano reset odizolowanej konfiguracji przed każdym testem oraz zatrzymanie
sesji w cleanup, także przy błędzie asercji. Kompilacja poprawki i Lint:
BUILD SUCCESSFUL (3 min 48 s, 85 zadań; testy JVM bez zmian, UP-TO-DATE).

Pierwsza powtórka po przebudowie: 3 niepowodzenia `No compose hierarchies`.
Odczyt systemu potwierdził wtedy `showing=true`, `mWakefulness=Dozing`.
Po wybudzeniu i zwykłym `wm dismiss-keyguard` wszystkie trzy ciche testy
uruchomione razem: **OK (3 tests)**, 8,485 s. Potwierdzono stan początkowy,
regulację tempa i odtworzenie ustawień po obrocie. W tej próbie przyczyną
braku drzewa UI był stan ekranu; nie dowodzi to przyczyny wszystkich dawnych
błędów M1. Nie uruchamiano dźwięku ani wibracji. Dalsze próby audio czekają
na potwierdzenie użytkownika.

## Stan bramek odbioru

1. Android StorageTest: zaliczony na Samsungu; na Lenovo jeszcze niewykonany.
2. Trzy ciche testy UI zaliczone; dwa testy audio/UI, ekrany danych i ręczny
   odsłuch pozostają do sprawdzenia.
3. Home, blokada ekranu, powiadomienie Stop, focus, odłączenie słuchawek,
   odmowa powiadomień, usunięcie zadania i przerwanie procesu.
4. 30 min odtwarzania przy zablokowanym ekranie, zero underrunów,
   pomiar zużycia zasobów i fizycznego audio/wibracji.
5. Samsung SM-A145R/API 35 oraz Lenovo/LineageOS bez Google Play Services;
   Galaxy A56 nie był dotąd testowany.
6. GitHub Actions po publikacji repozytorium.

Otwarte problemy z M1 — brak drzewa Compose w powtórce i niepotwierdzona
flaga utrzymania ekranu — nie są uznane za naprawione samą zmianą architektury.
Ich historia jest w [raporcie M1](M1-VALIDATION.md). Brak stabilnego USB nie
jest automatycznym wyjaśnieniem błędu aplikacji lub testu.

## Dane i ograniczenia

Ustawienia nie uruchamiają automatycznie audio. Presety używają tego samego
wersjonowanego kodeka co ustawienia. Baza ma schemat 1, bez destrukcyjnych
migracji. Usunięcie presetu wymaga potwierdzenia i nie usuwa historii.
Przy błędzie odczytu/zapisu pokazywany jest komunikat; baza nie jest kasowana.
Nowy runner instrumentacji tworzy testową aplikację z bazą w pamięci
i osobnym plikiem preferencji. Dotyczy to także testów interfejsu/audio:
ich ustawienia nie nadpisują danych aplikacji produkcyjnej.

Czas pochodzi z odtworzonych ramek, a nie czasu produkowania PCM. Planowana
cisza jest ćwiczeniem. Checkpoint jest zapisywany co 5 s; przy przerwaniu
procesu możliwa jest utrata końcówki, a błędy dysku mogą wydłużyć ten okres.
Po następnym otwarciu niedokończone rekordy oznaczane są jako przerwane.
Granice dni wyznaczane są w strefie zapisanej przy rozpoczęciu sesji.

Wyświetlane są ostatnie 100 sesji, 14 dni i 12 tygodni; starsze dane nie są
usuwane i nadal wchodzą do sumy całkowitej. Tryb 6/8 zachowuje ósemkowe BPM;
podziały są względne do tej jednostki. Wibracje i wizualizacja są odbiorcami
zegara, bez gwarancji idealnego fizycznego zgrania z głośnikiem.

Nie rozpoczęto M6. Nie publikowano wydania ani nie wykonywano commita:
udostępniony katalog `.git` nie jest działającym repozytorium Git.
