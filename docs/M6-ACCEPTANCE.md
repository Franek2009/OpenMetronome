# M6 — odbiór sprzętowy

## Aktualizacja 0.13 — 2026-10-30

0.13.0-beta.1: Samsung i Lenovo zaliczyły po 38 cichych testów; na każdym
6 prób audio pominiętych, zero innych pominięć i błędów. To zamyka wcześniejszy
brak pełnego cichego raportu Lenovo dla bieżącej wersji, nie historycznych
przebiegów. Osobna kopia validation nie zastępowała instalacji użytkownika.
Oficjalny podpisany APK zaliczył na emulatorze instalację i ponowną instalację
z zachowaniem ustawień. Odbiór audio i długie sesje pozostają otwarte.
[Raport 0.13](V0.13-VALIDATION.md). Stan beta.2 z zabezpieczeniami backupu:
[oddzielny raport](V0.13-BETA2-VALIDATION.md).

Poniżej zachowano historyczne wyniki i nadal otwarte kryteria sprzętowe.

## Stan i granice

Zatwierdzono przygotowanie odbioru, bez nowych funkcji, NDK i publikacji 1.0.
Podstawowy odsłuch potwierdzony przez użytkownika: „dźwięk działa”. Brak danych
o BPM, trybach i pomiarach; nie rozszerzamy znaczenia tego potwierdzenia.
Wyniki automatyczne wersji 0.5: 59 JVM, 14 cichych testów na Samsungu i następnie
2 krótkie testy audio zaliczone w osobnym przebiegu (9,311 s, bez pominięć).
Dwa długie testy audio nadal niewykonane. Szczegóły: [raport](M2-M5-VALIDATION.md).
Wersja 0.6: 75 JVM i 16 cichych testów Samsunga zaliczone, 4 audio pominięte;
odsłuch nowych barw nadal otwarty. [Raport 0.6](V0.6-VALIDATION.md).
Wersja 0.6.1: 79 JVM i kompilacja/lint zaliczone; na Samsungu 21 cichych
testów zaliczonych, 4 audio pominięte, bez błędów i innych pominięć.
APK zaktualizowane bez kasowania danych. [Raport 0.6.1](V0.6.1-VALIDATION.md).
Wersja 0.7: 93 JVM, Lint 0/0 w offline, oba APK zainstalowane na Lenovo
TB-X505F / Android 13 (GSI). Po odblokowaniu 26 cichych testów PASS, 4 audio
pominięte, zero błędów i innych pominięć. Oceniono zrzut głównego UI oraz
otwarcie/anulowanie systemowego zapisu CSV. To nie jest odbiór audio na
tablecie ani zakończenie M6. [Raport 0.7](V0.7-VALIDATION.md).

**Aktualnie bez kolejnych prób audio i wibracji.** Wyniki adaptacyjnego UI i
zakres wcześniejszych prób: [raport 0.7.1](V0.7.1-VALIDATION.md).

Historyczna zgoda obejmowała dwa krótkie testy audio, bez wibracji. Użytkownik odpowiedział
„Tak możesz z głosem” na pytanie o Start/Stop i obrót/przejście do tła.
Pierwsza próba pominęła oba testy z powodu blokady ekranu (0,17 s; kod -4).
Po ręcznym odblokowaniu oba wykonano z rzeczywistym silnikiem i zaliczono:
9,311 s, zero błędów i pominięć. Po zakończeniu testy potwierdziły STOPPED,
a `dumpsys activity services org.openmetronome` zwrócił `(nothing)`.
Uzgodniony przebieg jest zakończony. Pozostałe scenariusze wymagają osobnej zgody.
Użytkownik odłożył test 30-minutowy do własnego zwykłego użytkowania; nie
uruchamiamy go automatycznie i nie zaliczamy. Nie blokuje rozwoju wersji dev.
Wibracje wymagają osobnej zgody. Nie używać zerowej głośności
ani słuchawek jako obejścia. Do tego czasu checklistę sprzętową pozostawić otwartą.

## Kolejność i kryteria odbioru

- [x] Podstawowy odsłuch zgłoszony jako działający przez użytkownika.
- [x] Pełny dostępny cichy zestaw na Samsungu: 14 zaliczonych, bez błędów.
- [x] Zgoda na dwa krótkie automatyczne testy audio, bez wibracji.
- [x] Wielokrotny Start/Stop: 5 cykli PLAYING → STOPPED oraz zwolnienie
  KEEP_SCREEN_ON po Stop, zgodnie z asercjami testu.
- [x] Obrót i przejście do tła: sesja pozostaje PLAYING, stan jest odtworzony,
  Stop działa; KEEP_SCREEN_ON sprawdzone podczas odtwarzania. Przejście do tła
  było krótkim obniżeniem stanu Activity do CREATED, nie próbą długiego uśpienia.
- [ ] Odsłuch funkcji: BPM 20/137/300, akcent pierwszego pulsu, metrum 4/4 i 6/8
  (w 6/8 BPM oznacza ósemki), podziały i własne akcenty, wyciszenia 0/50/100%,
  cykl 2 takty słyszalne / 2 ciszy, progresja i rampy w obu kierunkach.
- [ ] Integracja: Home, blokada ekranu, Stop z powiadomienia, utrata focus,
  odłączenie słuchawek (jeśli używane), brak samoczynnego wznowienia.
  Przed testem powiadomienia użytkownik musi udzielić zgody na powiadomienia;
  sprawdzić także odmowę. Nie zmieniać tej zgody potajemnie przez ADB.
- [ ] Test 30-minutowy odłożony przez użytkownika do własnego użytkowania,
  niewykonany, bez automatycznego uruchamiania.
- [ ] Długa próba: PLAYING przez cały okres, rosnący playback head, zero
  underrunów, brak samoczynnego wybudzenia, zatrzymanie i końcowy zapis sesji.
  Czas zapisu porównać z odtworzonymi ramkami, nie z liczbą wyprodukowanych próbek.
- [x] Lenovo TB-X505F bez Google Play Services: cichy zestaw 0.13.0-beta.1,
  38 PASS, 6 audio pominiętych, zero innych pominięć i błędów.
- [ ] Lenovo: krótki odsłuch i podstawowe sterowanie/tło po odpowiedniej zgodzie.
- [ ] Osobny odbiór wibracji po zgodzie; brak tej zgody nie blokuje prób audio.

## Przygotowane polecenia — wyłącznie po odpowiedniej zgodzie

Najpierw sprawdzić stabilność ADB, odblokowanie ekranu, zainstalowaną wersję
i brak trwającej sesji. Zapisać w raporcie model, Android, APK, trasę audio,
warunki zasilania i zakres zgody. Testy używają izolowanego magazynu;
nie czyścić danych zwykłej aplikacji. Oba APK aktualizować przez `install -r`.

Dwa krótkie testy, wybrane jawnie, aby nie uruchomić przypadkiem długiej próby:

```bash
source scripts/dev-env.sh
adb -s NUMER_SAMSUNGA shell am instrument -w -r \
  -e allowAudio true -e allowHaptics false \
  -e class 'org.openmetronome.ui.MetronomeScreenTest#repeatedStartStopDoesNotLeaveAudioPlaying,org.openmetronome.ui.MetronomeScreenTest#playbackSurvivesRecreationAndLeavingForeground' \
  org.openmetronome.test/org.openmetronome.OpenMetronomeTestRunner
```

30 minut, dopiero po osobnej zgodzie na długie odtwarzanie:

```bash
adb -s NUMER_SAMSUNGA shell am instrument -w -r \
  -e allowAudio true -e allowHaptics false -e longAudio true \
  -e class 'org.openmetronome.ui.LongAudioSessionTest#thirtyMinutesWithScreenOffWithoutUnderruns' \
  org.openmetronome.test/org.openmetronome.OpenMetronomeTestRunner
```

Raport długiego testu: `cache/audio-screen-off-session.txt`, dostępny przez
`adb shell run-as org.openmetronome cat cache/audio-screen-off-session.txt`.
Nie uruchamiać całej klasy LongAudioSessionTest z flagami audio: zawiera też
oddzielną 30-minutową próbę z ekranem włączonym.

## Błędy i zakończenie

Przy pierwszym błędzie zatrzymać odtwarzanie, zachować raport i ograniczony
do aplikacji logcat. Nie powtarzać długiej próby w pętli bez analizy.
Przy zerwaniu ADB nie zakładać, że odtwarzanie ustało; użyć Stop w aplikacji
lub powiadomieniu, a stan testu oznaczyć jako niepotwierdzony.
Naprawy wprowadzać małymi zmianami, z odpowiednimi regresjami i ponowną próbą
nieudanego scenariusza. Nie przebudowywać interfejsów silnika bez wykazanej potrzeby.

Raportować osobno PASS, FAIL, pominięcia, odsłuch użytkownika i pomiary.
Zero underrunów nie jest pomiarem fizycznego jitteru ani synchronizacji wibracji.
Zaliczenie powyższych prób nie stanowi automatycznej zgody na wydanie 1.0.
