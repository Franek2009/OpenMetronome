# Diagnostyka DEV — PCM, magazyn i raporty

> **Zakres beta po decyzji użytkownika:** pełna rejestracja sesji DEV jest
> wyłączona (`DEV_CAPTURE_QUALIFIED=false`), ponieważ nie zaliczyła budżetu
> narzutu API26. Poniższy opis dotyczy przygotowanej implementacji, nie aktywnej
> funkcji beta. Zwykłe funkcje, historia i statystyki DEV działają. Istniejące
> raporty pozostają czytelne/eksportowalne. Kwalifikacja ma osobny workflow
> `DEV recorder qualification (inactive in beta)`; zachowuje próg +10% i jawne FAIL.
> Domyślna regresja testuje wyłączoną fabrykę i wszystkie pozostałe funkcje,
> pomijając wyborem zestawu wyłącznie oznaczony benchmark. Nie zalicza go jako PASS.


Implementacja pełnego toru (nieaktywna w beta) obejmuje: PCM, sterowanie,
UUID historii, cykl usługi, focus/noisy oraz wyniki checkpointów. PROD dostarcza
puste punkty integracji i nie zawiera implementacji magazynu DEV.
[Zdarzenia i ZIP v1](DEV-TRACE-EXPORT.md).
[Kwalifikacja i granice pomiarów](V1.1-DEV-INTEGRATION-VALIDATION.md).

## Granica produkcji i DEV

Wspólna warstwa zawiera wyłącznie typy obserwacji i opcjonalne punkty w silniku.
Domyślny brak obserwatora nie tworzy bufora ani nie odczytuje zegara diagnostycznego.
`BoundedPcmTrace` i konsumencki `PcmTraceRecord` są w source set DEV, poza APK prod.
Inspekcja artefaktów sprawdza ich obecność w devRelease i nieobecność w prodRelease.
Integracja diagnostyki nie zmienia brzmień ani identyfikatorów aplikacji i nie
dodaje uprawnień. Statystyki 2.0 mają osobno opisaną migrację Room i backup v2.
DEV używa osobnego trwałego certyfikatu; PROD zachowuje dotychczasowy.

Fabryka bufora jest wywoływana przez `start` przed workerem audio. Jej wyjątek
jest logowany bez odrzucania Start. Sam obserwator izoluje wyjątki zwykłe
`Exception`: rejestracja jest oznaczana jako uszkodzona, kolejne próby zwiększają
liczniki strat, a audio nie jest zatrzymywane. Nie obiecujemy przetrwania
krytycznej awarii VM/OOM. Wyjątki nie trafiają jako nieograniczone teksty do rekordów.

## Zdarzenia i jednostki

Każdy rekord ma sekwencję próby od zera, monotoniczne nanosekundy, rodzaj,
ramkę PCM i pola liczbowe a–d. `config` jest opcjonalnym odwołaniem do
niezmiennej konfiguracji, bez formatowania tekstu na workerze.
`PcmTraceClock` zwraca prymitywne Long, bez boksowania każdej obserwacji.
Kody rodzajów są jawne i stabilne; ordinal jest wyłącznie wewnętrznym indeksem
pierścienia tego samego procesu, nie przyszłym identyfikatorem pliku.

Nanosekundy opisują chwilę obserwacji CPU, nie zaplanowany czas emisji akustycznej.
Ramka to pozycja w strumieniu mono. `-1` oznacza niedostępną/niezdefiniowaną pozycję,
a nie zmierzone zero. Zdarzenie BEAT opisuje każdy slot osiągnięty przez renderer, przed wyborem
sampla, także wyciszony lub taki, którego sample następnie zawiedzie.
Nie oznacza, że slot został wyrenderowany, wysłany, odtworzony lub usłyszany.

| Kod i rodzaj | Ramka | a / b / c / d |
| --- | --- | --- |
| 1 SESSION_BEGIN | 0 | lokalny numer sesji silnika; config początkowy |
| 2 OUTPUT_OPENED | 0 | sampleRate / blockFrames / bufferFrames / początkowe underruny |
| 3 CONFIG_OBSERVED | początek bloku | config: ostatnie żądanie odczytane z AtomicReference |
| 4 CONFIG_APPLIED | bieżący główny puls | snapshot appliedConfig; patrz wyjątek głośności poniżej |
| 5 EFFECTIVE_CONFIG | bieżący główny puls | snapshot activeConfig, w tym wynik ramp/progresji |
| 6 BEAT | slot podziału | takt od 0 / puls od 0 / slot od 0 / maska flag |
| 7 RENDER | początek bloku | liczba ramek / koniec wyłączny / czas renderowania ns lub -1 |
| 8 WRITE_BEGIN | początek próby transferu | żądana liczba / offset w bloku |
| 9 WRITE_RESULT | początek próby transferu | żądana liczba / zwrócona liczba / offset w bloku |
| 10 WRITE_THROW | początek próby transferu | żądana liczba / offset w bloku / raw code / flaga dostępności |
| 11 PLAY | 0 | ramki pełnych bloków wypełniających bufor przed play |
| 12 HEAD | playback head | ramki pełnych bloków zapisanych przez silnik |
| 13 OUTPUT_STATUS | playback head | ramki pełnych bloków / przyrost underrunów od startu |
| 14 ERROR | -1 | kod AudioFailure (słownik builda w metadata.json) / raw code / flaga dostępności |
| 15 CLEANUP_FAILURE | -1 | zasób: pause=0, flush=1, track=2, focus=3, bank=4 |
| 16 SESSION_END | -1 | flaga anulowania 0/1 / kod AudioFailure lub -1 |
| 17 VOLUME_TARGET | początek przejścia gain | poprzedni cel % / nowy cel % / długość przejścia w ramkach |
| 18 FOCUS_REQUESTED | -1 | żądanie platformy (worker) |
| 19 FOCUS_RESULT | -1 | surowy wynik żądania (worker) |
| 20 FOCUS_RELEASE | -1 | surowy wynik zwolnienia focus (worker) |

CONFIG_APPLIED/EFFECTIVE_CONFIG to snapshoty na pulsie. Program zmienia się na
najbliższej niewyrenderowanej granicy taktu, lecz głośność ma odrębne przejście
od początku bloku. VOLUME_TARGET podaje jego ramkę bez udawania oczekiwania
na takt. To nadal nie pomiar fizycznej głośności ani dokładnego czasu kliknięcia.

Maska BEAT: trening=1, MUTE pulsu=2, MUTE podziału=4, wybór akcentowanej próbki=256, odliczanie=512.
Flagi łączą się. Akcent może być zaplanowany na wyciszonej pozycji.
Trening na tym etapie jest wspólną przyczyną: nie rozróżniamy jeszcze losowej
ciszy od ciszy cyklicznej. Głośność 0 nie jest flagą treningowej ciszy.

Rejestrowana jest każda próba `PcmSink.write`, również częściowa, zerowa,
błędna i przerwana przez Stop. Pozycja i liczba przyjętych ramek są odrębne
od playback head. Przy Stop częściowo przyjęty blok może nie zostać uwzględniony
w dotychczasowym liczniku pełnych bloków `framesWritten`; rekordy WRITE_RESULT
zachowują rzeczywiste zwroty sinka. Nie przypisujemy im fikcyjnej pozycji odtworzenia.
OUTPUT_STATUS korzysta z istniejących odczytów underrunów, bez dodatkowego
odpytywania platformy przy każdym podziale.

## Protokół i granice pamięci

- Jeden producent: worker danej sesji; jeden konsument poza workerem.
- Pola są wpisywane do prealokowanych tablic, następnie publikowany jest indeks
  z release/acquire. Konsument kopiuje rekord przed zwolnieniem slotu.
- Pełny pierścień odrzuca nowy rekord. Nie czeka i nie nadpisuje starego.
- Sekwencja rośnie także dla strat; luki nie są renumerowane ani ukrywane.
- `health()` zwraca attempted/accepted/dropped, straty według rodzaju,
  faulted/closed. Podczas niespójnego odczytu zwraca null, nie sztuczne statystyki.
- Wyjątek obserwatora wyłącza jego append do końca sesji; dalsze próby nadal
  są liczone jako utracone. Przepełnienie nie wyłącza bufora: konsument może
  zwolnić miejsce dla kolejnych zdarzeń.
- Maksymalnie 32768 slotów; stały budżet całego przyszłego transportu 4 MiB.
  Konserwatywne oszacowanie tablic audio z referencjami: 2229536 B. To nie
  deklaracja całkowitego heap/PSS: współdzielone obiekty konfiguracji, konsumenckie
  DTO i narzut VM wymagają osobnego pomiaru.
- W pierścieniu może pozostawać najwyżej 512 rekordów z referencją konfiguracji,
  niezależnie od liczby slotów. Odrzucenie deskryptora jest raportowaną stratą;
  nie blokuje przyjmowania BEAT. Konsument usuwa referencję przy odczycie.
- Etap 2 nie obejmował dysku ani ZIP. Obecne etapy 3a/3b przygotowują magazyn
  i eksport z limitami 512 MiB/sesję oraz 2 GiB łącznie, bez automatycznego kasowania.

## Testy i pozostałe prace

JVM sprawdza: PCM i pulsy z/bez obserwatora przy 44,1/48 kHz, wszystkie podziały,
ramki i przyczyny ciszy, zmiany programu/głośności, każdą częściową próbę zapisu,
anulowanie, błędy sinka/cleanup, wyjątek fabryki/obserwatora/zegara, przepełnienie,
luki, obieg slotów, limit referencji, zatrzymanego konsumenta oraz współbieżny odczyt.
Test HotSpot mierzy alokacje producenta po rozgrzaniu; nie zastępuje pomiaru ART.

Zapis i eksport połączono z aplikacją. Fabryka DEV tworzy jeden magazyn procesu,
a każda instancja silnika ma osobny UUID. UUID historii pochodzi z SessionRecorder.
Usługa kończy kanał sterowania po terminalnym stanie silnika i końcowym zapisie;
anulowanie jej scope nie anuluje skończonego zadania finalizacji aplikacji.
Błąd dysku nie blokuje audio, a raport podaje straty i stan incomplete/interrupted.
Odrzucony Start przed powstaniem producenta PCM ma osobny raport próby.

Kwalifikacja ART: pełny worker i rzeczywisty konsument IO, lecz atrapy sink/focus.
Trzy pomiary: baza / DEV / baza, niższe bazowe p99 jako odniesienie. Wymagamy
identycznego PCM, zerowych strat w standardowym scenariuszu oraz wzrostu p99
renderowania do 10%. Szczegóły i ograniczenia w raporcie powyżej. Nie utożsamiamy
pomiaru CPU ani PSS emulatora z jitterem, latencją lub narzutem na każdym telefonie.

Historyczne raporty etapów: [PCM](V1.1-PCM-TRACE-VALIDATION.md),
[magazyn](V1.1-DEV-STORAGE-VALIDATION.md), [eksport](DEV-TRACE-EXPORT.md).
