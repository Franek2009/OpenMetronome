# Diagnostyka DEV — PCM, magazyn i raporty

Status: obserwator PCM, magazyn i eksport są przygotowane i testowane osobno,
**bez aktywacji w uruchamianej aplikacji**. `OpenMetronomeApplication` nie
przekazuje jeszcze traceFactory ani ControlObservation. Integracja usługi,
storage i kwalifikacja całego toru ART pozostają kolejnym etapem.
[Zdarzenia i ZIP v1](DEV-TRACE-EXPORT.md). Nie ogłaszamy ukończenia pełnej diagnostyki DEV.

## Granica produkcji i DEV

Wspólna warstwa zawiera wyłącznie typy obserwacji i opcjonalne punkty w silniku.
Domyślny brak obserwatora nie tworzy bufora ani nie odczytuje zegara diagnostycznego.
`BoundedPcmTrace` i konsumencki `PcmTraceRecord` są w source set DEV, poza APK prod.
Inspekcja artefaktów sprawdza ich obecność w devRelease i nieobecność w prodRelease.
Nie zmieniono Room, `.ombak`, presetów, brzmień, identyfikatorów aplikacji,
wersji ani konfiguracji kluczy. Nie ma nowych uprawnień.

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
| 7 RENDER | początek bloku | liczba ramek / koniec wyłączny |
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

Maska BEAT: trening=1, MUTE pulsu=2, MUTE podziału=4, wybór akcentowanej próbki=256.
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

Zapis i eksport przygotowano w etapach 3a/3b. Pozostaje podłączenie do aplikacji:
UUID instancji silnika i historii, sterowanie, focus/noisy oraz storage/retry.
Punkty obserwacji i słowniki nie oznaczają aktywnego rejestrowania tych danych.

Przed aktywacją: globalna kontrola budżetu dla wszystkich kanałów i sesji,
pomiary całego toru i pamięci na ART; budżet p99 renderowania
+10% oraz brak strat w standardowym scenariuszu zgodnie z planem. Nie są jeszcze
zaliczone. Pomiary fizycznego audio i wibracje wymagają osobnej zgody.

[Raport walidacji etapu 2](V1.1-PCM-TRACE-VALIDATION.md).

## Etap 3a — trwały magazyn bez aktywacji

Dodano procesowy konsument IO, ograniczoną rezerwację pamięci i dysku,
checkpointy, CRC rekordów i odzyskiwanie niedokończonych raportów.
[Zakres, protokół i walidacja](V1.1-DEV-STORAGE-VALIDATION.md).
Nie podłączono jeszcze magazynu do aplikacji ani nie wdrożono eksportu ZIP;
pełna kwalifikacja ART pozostaje warunkiem aktywacji.

## Etap 3b — sterowanie i eksport (bez aktywacji)

[Protokół ZIP, API, ograniczenia i walidacja](DEV-TRACE-EXPORT.md).
Dodano osobny kanał sterowania, powiązanie historii, zachowanie native error
codes i focus, eksport CSV/JSON ZIP oraz listę raportów wyłącznie DEV.
Aplikacja nadal nie przekazuje obserwatorów do silnika. Integracja usługi,
storage i kwalifikacja narzutu pozostają kolejnym PR.
