# Architektura OpenMetronome — M2–M5

## Panel ćwiczeń 0.7

Ciemny motyw jest stały, również przy jasnym ustawieniu systemu. Trzy ekrany:
Metronom, Statystyki, Ustawienia; Presety z paska. Start/Stop pozostaje poza
przewijaniem. Od 0.7.1 czysta polityka `InstrumentLayout` wybiera telefon
pion/poziom i tablet pion/poziom. Używa dostępnej szerokości (próg 600 dp)
oraz wysokości okna z konfiguracji (próg kompaktowy 480 dp), niezależnej od
klawiatury. Tablet pion ma pełnej szerokości instrument nad dwoma panelami;
poziom instrument obok niezależnie przewijanych ustawień. Trening tabletu
jest domyślnie rozwinięty. Niski telefon ma kompaktowy nagłówek i dwie kolumny.
Stan otwarcia edytora BPM jest ponad gałęziami układu, szkic pola używa
`rememberSaveable`; klawiatura i obrót nie zamykają dialogu ani nie stosują
niezatwierdzonej wartości. Lampki to Canvas z semantyką przycisków, nie bitmapy referencji.
Ich wzór pokazuje ustawienie żądane, obwódka odtworzony puls. Komunikat
oczekującej zmiany nadal odróżnia ustawienie od zastosowania w rendererze.

`PracticeDao.sessionsForExport()` zwraca wszystkie sesje w jednym odczycie
SQL, niezależnie od limitu 100 na ekranie. `SessionCsv` jest czystym formatterem
piszącym do Writer. `AppData.exportCsv` działa w procesowym scope IO, bez
zależności od Activity i bez zatrzymywania sesji. Systemowy CreateDocument
udostępnia wyłącznie plik wskazany przez użytkownika. Anulowanie nie uruchamia
eksportu, błąd nie modyfikuje bazy. Stan eksportu przeżywa obrót/zmianę ekranu,
ale nie ubijanie procesu. Eksport nie jest kopią zapasową do importu.

## Warstwy i własność sesji

Jeden moduł `app`. Pakiety `domain` i `audio` nie importują Androida.
Nie używamy DI frameworka, NDK ani usług Google.

```text
Compose → ViewModel → MetronomePort / PlaybackClient
                          ↓ lokalne połączenie z usługą
                   MetronomeService (jedyny właściciel sesji)
                          ↓
                   MetronomeController → PcmAudioEngine → PcmSink / AudioTrack
                                             ↓
                                        PcmRenderer
                                      ↙      ↓       ↘
                            BeatScheduler TrainingController SilencePlanner
                                             ↓
                                       ClickGenerator

Stan i odtworzone ramki → UI / wibracje / SessionRecorder → Room
Konfiguracja żądana → SettingsRepository → Preferences DataStore
```

ViewModel przeżywa obrót. Jego zamknięcie odłącza klienta, ale nie zamyka
aktywnej usługi. Serwis jest nieeksportowany; start następuje po poleceniu
użytkownika. Home, usunięcie zadania i blokada nie są poleceniem Stop.
`START_NOT_STICKY` wyklucza samoczynne odtwarzanie po zabiciu procesu.

Usługa tworzy kanał powiadomień, MediaSession i powiadomienie z akcją Stop.
Focus jest uzyskiwany dopiero po wejściu do foreground. Odmowa lub utrata
focus, odłączenie słuchawek i błędy audio kończą sesję bez automatycznego retry.
Zasoby audio, focus, wakelock i powiadomienie są zwalniane na końcu sesji.
CPU pozostaje aktywne wyłącznie podczas uruchamiania/odtwarzania.
Wakelock ma timeout 120 s, odnawiany co 60 s przez aktualizacje stanu sesji,
i jest jawnie zwalniany po zatrzymaniu audio. Odnawianie nie planuje kliknięć;
zatrzymanie aktualizacji nie pozostawia blokady CPU bezterminowo.
Zgoda na powiadomienia jest osobną czynnością użytkownika; aplikacja nie
blokuje odtwarzania wyłącznie z powodu jej odmowy.

## Próbki, rytm i precyzja

Pozycja to `Long` w ramkach mono PCM16. Częstotliwość pochodzi z wyjścia
AudioManager (fallback 48 kHz), blok z jego właściwości (fallback 256 ramek).
Bufor mieści co najmniej minimum AudioTrack i cztery bloki; jest wypełniany
przed `play`. Żądanie low latency nie gwarantuje szybkiej ścieżki sprzętowej.

`BeatScheduler` przechowuje dokładną pozycję w jednostkach
`1 / LCM(20…300)` ramki jako BigInteger. Dopiero pozycje zdarzeń są
zaokrąglane w dół. Podziały obliczane są z niezaokrąglonego początku i okresu
pulsu, nie przez dzielenie zaokrąglonej liczby ramek. To zachowuje błąd
kwantyzacji mniejszy od jednej ramki bez kumulacji, także przy triolach.
BigInteger jest używany przy zdarzeniach, nie w każdej próbce.

AudioTrack konsumuje ciągły strumień swoim zegarem. Blokujący zapis reguluje
produkcję, nie pozycje kliknięć. Silnik obsługuje krótkie zapisy, anulowanie,
błędne wyniki i ERROR_DEAD_OBJECT. Zatrzymanie przerywa zapis przez pause;
worker osobno próbuje flush/release i oddać focus. UI nie wykonuje join.
Nowa sesja zaczyna się od ramki zero i nowego taktu. Nie ma sleep/delay
wyznaczającego uderzenia.

W /4 główny puls jest ćwierćnutą, w 6/8 ósemką (sześć na takt). Podziały:
1×, 2×, triole, 4× oraz 3:1. Własne akcenty podziałów powtarzają się wewnątrz
pulsu. Od 0.7 `beatAccents` określa osobno każdą pozycję głównego pulsu:
NORMAL/ACCENT/MUTE. Pusta lista oznacza starszą konfigurację z `accentFirstBeat`;
jawna lista ma długość zgodną z metrum. Zmiana metrum przez `withTimeSignature`
zachowuje wspólne pozycje, nowe wypełnia NORMAL. Edycja materializuje cały wzór.
MUTE pulsu oraz cisza treningowa wyciszają wszystkie jego podziały. ACCENT pulsu
podkreśla pierwszy podział, ale nigdy nie odwołuje MUTE podziału. Wzór wchodzi
na granicy taktu; oś czasu i losowanie raz na puls pozostają bez zmian.

## Trening i konfiguracja

`SilencePlanner` losuje raz na główny puls, także w cichym takcie. Wycisza
cały puls z podziałami. Cykl rozpoczyna się od taktów słyszalnych.
Warunki losowy i taktowy łączone są alternatywą. Każdy renderer ma własne
ziarno losowości; testy mogą wstrzyknąć powtarzalny generator. Cisza nie zmienia osi czasu,
liczników, ramp ani czasu ćwiczenia. Random jest wstrzykiwany do testów.

`TrainingController` jest wywoływany na granicach taktów. Progresja ciszy
korzysta z upływu ramek audio; rampy z liczby zakończonych taktów. Cel tempa
nie jest przekraczany, procent wyciszeń nie przekracza 100. Ręczna zmiana BPM
wyłącza rampę. Zmiana programu (tempo, metrum, cisza, progresja, rampa) resetuje
postęp na najbliższej niewyrenderowanej granicy taktu. Start resetuje trening.
Wizualizacja i zmiana akcentów nie resetują programu.

Rozróżniamy konfigurację żądaną, zastosowaną do programu i efektywną
(zmienianą przez trening). Ostatnia żądana konfiguracja wygrywa; zmiany
wchodzą zbiorczo na granicy taktu. Audio już zapisane w buforze nie jest
przepisywane. Wczytanie presetu jest takim samym poleceniem konfiguracji,
nie poleceniem Start.

## Odtworzony puls i lokalne dane

Pozycja odtwarzania pochodzi z `playbackHeadPosition`, rozszerzonej z uint32
na Long. To ramki skonsumowane, nie liczba ramek wysłanych do kolejki.
Kolejka zdarzeń renderera jest opróżniana do ostatniego odtworzonego pulsu.
UI i wibracje są odbiorcami; mogą być opóźnione przez system, ale nigdy nie
sterują zegarem audio. Główny puls jest sygnalizowany również podczas ciszy.
Wibracje są domyślnie wyłączone.

Preferences DataStore przechowuje żądaną konfigurację. Wspólny `ConfigCodec`
koduje wersjonowane pola liczbowe/enum jako UTF-8 key=value. Dekoder waliduje
wersję i modele. Odczyt nie uruchamia audio; błąd nie kasuje oryginalnego pliku.
Globalne ustawienia wizualizacji/wibracji również są częścią konfiguracji.

Room 1: presety, sesje i części sesji przypisane do lokalnych dni. Zmiana
nazwy nie zmienia konfiguracji; zastąpienie ustawieniami jest osobną akcją.
Usunięcie presetu wymaga potwierdzenia, bez usuwania historii. Schemat jest
eksportowany do repozytorium; brak fallbackToDestructiveMigration.

SessionRecorder korzysta z ramek odtworzonych, zapisuje checkpoint co 5 s
i na końcu. Pierwsze odtworzone ramki tworzą sesję; sama inicjalizacja nie.
Czas jest zakotwiczony w rozpoczęciu i rośnie według audio, więc zmiana zegara
ściennego nie wydłuża ćwiczenia. Dni dzielone są według zapisanej strefy,
z uwzględnieniem DST. Po ponownym otwarciu niedokończone rekordy oznaczane są
jako przerwane. Zapisy, odczyty i agregacje nie wykonują się na workerze audio.
Backup/transfer danych aplikacji jest wyłączony.

## Weryfikacja i granice

Wersja 0.6.1 przenosi PlaybackControls poza przewijany obszar do głównego
Scaffold.bottomBar, dzięki czemu działa na wszystkich zakładkach. Sam ekran
metronomu zachowuje opcjonalny pasek dla podglądu i izolowanych testów.
Nie zmienia interfejsu silnika. DiagnosticReport jest czystym formatterem
z jawną listą danych wejściowych, bez dostępu do bazy i identyfikatorów urządzenia.
Okno przechowuje migawkę z chwili otwarcia; kopiowanie to osobna akcja platformowa.

Skrypt verify.sh wykonuje zawsze ciche zadania; opcjonalna instrumentacja ma
jawnie wyłączone audio i wibracje. Parser ocenia terminalne statusy testów,
kompletność raportu i zakończenie runnera, nie samo podsumowanie OK (N tests).
Nieaudialne pominięcie (np. blokada ekranu) oznacza niepełny wynik, kod wyjścia 2.

Wersja 0.6 dodaje `SoundConfig(volumePercent, clickSound)` do konfiguracji.
Kodek nadal zapisuje wersję 1 z opcjonalnymi nowymi polami; brak pól oznacza
100% i CLASSIC. Schemat Room nie zmienia się. `TapTempoEstimator` przyjmuje
monotoniczne nanosekundy, a ViewModel przekazuje wynik jako zwykłą edycję BPM,
bez odtwarzania. Nie jest to część zegara audio i nie jest utrwalany.

Renderer przygotowuje na start bank zwykłych/akcentowanych próbek trzech barw.
Barwa podlega tej samej granicy taktu co rytm. Głośność jest niezależnym
parametrem renderera: `VolumeEnvelope` zmienia gain liniowo przez około 5 ms
liczone próbkami, także w ciszy. Kolejna edycja rozpoczyna przejście od bieżącej
wartości, bez skoku. Buforowane już próbki pozostają niezmienione.
`activeConfig` i `appliedConfig` uwzględniają aktualny cel głośności, nie
przedstawiając jej jako zmiany oczekującej na takt. Nie wyprzedzają zmiany
barwy, tempa ani rytmu. Edycje dźwięku nie resetują postępu treningu.

JVM: modele, harmonogram 24 h, wszystkie BPM, losowość, cykle, podziały,
progresja, rampy, PCM, błędy audio, kodek i granice dni/DST. WAV jest artefaktem
testowym. Instrumentacja obejmuje UI/audio, izolowane Room/DataStore i
jawnie uruchamianą próbę 30-minutową. Testy nie mogą korzystać z produkcyjnej
bazy ani ustawień użytkownika.

Platformowy punkt składania w `OpenMetronomeApplication.createAudioEngine`
zwraca rzeczywisty silnik dla usługi; `vibratePulse` jest osobnym portem efektu.
TestApplication opakowuje start silnika blokadą wymagającą `allowAudio=true`,
zanim silnik pozyska focus lub utworzy AudioTrack. Wibracje mają osobną
blokadę `allowHaptics=true`. Nie zmienia to harmonogramu ani zachowania
zwykłej aplikacji. Oznaczone `@RequiresAudio` testy są domyślnie pomijane
jeszcze przed uruchomieniem Activity; blokada silnika chroni dodatkowo przed
nieoznaczonym przypadkiem. Procedura: [cicha stabilizacja](QUIET-STABILIZATION.md).

Źródła platformowe: [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#media),
[audio focus](https://developer.android.com/media/optimize/audio-focus),
[Room](https://developer.android.com/jetpack/androidx/releases/room),
[DataStore](https://developer.android.com/jetpack/androidx/releases/datastore).

Wyniki, otwarte błędy M1 oraz niewykonane próby urządzeniowe:
[weryfikacja M2–M5](M2-M5-VALIDATION.md). Poprawny PCM i zero underrunów nie
dowodzą precyzji fizycznego wyjścia audio ani synchronizacji wibracji.
