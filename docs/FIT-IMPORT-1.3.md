# Garmin FIT — etap A

Etap A jest na main po audycie i scaleniu
[PR #49](https://github.com/Franek2009/OpenMetronome/pull/49).
[Pełne CI zatwierdzonego commita](https://github.com/Franek2009/OpenMetronome/actions/runs/46131104425)
zakończyło się wynikiem 7/7, w tym ciche testy PROD i DEV oraz aktualizacje
na API 26 i 35. Punkt wejścia
`FitActivityReader.read` przyjmuje strumień należący do wywołującego, opcjonalny
budżet współdzielony przez kolejne pliki oraz funkcję sprawdzającą anulowanie.
Zapis i kopie należą do etapu B, a wybór plików, podgląd i integracja do etapu C.
Etap B wymaga uzgodnienia pojemności kopii, migracji i deduplikacji.

## Obsługiwany format

- Jeden kontener FIT z nagłówkiem 12 lub 14 bajtów, protokół 1.0 lub 2.0.
  Odczyt obejmuje sygnaturę, długość, opcjonalne CRC nagłówka i końcowe CRC.
  Bajty za końcowym CRC są odrzucane. Rozpoznany początek drugiego kontenera
  jest klasyfikowany jako nieobsługiwany format.
- Pierwsza wiadomość danych musi być pojedynczym `file_id` z typem Activity.
  Obsługiwane są kolejne definicje tego samego typu lokalnego, obie kolejności
  bajtów, typy bazowe FIT 1.0/2.0 i skompresowany timestamp z istniejącym
  odniesieniem. Pola użyte w obliczeniach muszą mieć właściwy typ i rozmiar;
  wartości invalid nie stają się liczbami użytkowymi.
- Dla FIT 2.0 walidujemy strukturę i rozmiar pól developerskich, a następnie
  pomijamy ich treść. Nigdy nie zastępują natywnych pól czasu. Czytnik pomija
  też niepotrzebne wiadomości zgodnie z definicjami. Pełne timestampy takich
  wiadomości nadal mogą być odniesieniem dla kompresji.
- Każdy `Session` otrzymuje numer od zera w kolejności zapisu, także gdy jego
  podsumowanie zostanie odrzucone. Liczba z `Activity.num_sessions`, gdy obecna,
  musi się zgadzać. Kolejność zapisania wiadomości podsumowania nie ogranicza
  położenia zdarzeń w pliku.
- SHA-256 zwracamy po zaakceptowaniu całego pliku, łącznie z nagłówkiem i
  końcowym CRC. Parser nie wystawia wyniku po częściowym odczycie. Zachowuje
  czasy oraz opcjonalne kody sportu, bez danych zdrowotnych, GPS, identyfikatorów
  urządzeń i treści pól developerskich.

Źródła: [publiczna specyfikacja FIT](https://developer.garmin.com/fit/articles/fit-protocol/fit_protocol.html),
[dekodowanie plików Activity](https://developer.garmin.com/fit/articles/cookbook/decoding_activity_files.html)
i [zdarzenia timera w plikach Activity](https://developer.garmin.com/fit/articles/cookbook/encoding_activity_files.html).
Czytnik nie używa kodu ani zależności Garmin SDK.

Kompresja czasu dotyczy nagłówków rekordów lokalnych 0–3, których definicja
nie zawiera osobnego pola timestamp. Pierwszy taki rekord wymaga wcześniejszego
pełnego znacznika. Kolejne używają pięciu najmłodszych bitów sekund z uwzględnieniem
przejścia przez granicę 32 s. Z tych bitów nie da się rozpoznać pominiętego
pełnego obrotu. Przy przerwie co najmniej 32 s potrzebne jest nowe pełne
odniesienie. Czytnik nie rekonstruuje dodatkowych obrotów na podstawie
podsumowania ani nie przestawia rekordów przed rozwinięciem timestampów.
[Opis nagłówka skompresowanego](https://developer.garmin.com/fit/articles/fit-protocol/fit_protocol.html#compressed-timestamp-header)

## Rekonstrukcja czasu i wyniki

Start i zdarzenia są w UTC. Raportowane `total_elapsed_time` i
`total_timer_time` są zachowywane w milisekundach. Zdarzenia timera sortujemy
według czasu i zachowujemy kolejność pliku przy tej samej sekundzie. W znanej
grupie timera obsługujemy `start`, `stop` i `stop_all`. Na wspólnej granicy
zamknięcie należy do poprzedniej sesji, a następujący po nim start do kolejnej.
Jeśli w tej samej sekundzie po starcie występuje zamknięcie, obie sesje
zachowują tylko podsumowania. Powtórzenie tej samej krawędzi w tej samej
sekundzie jest pomijane. Sprzeczne powtórzenia i nieznane zdarzenia timera
powodują przejście do podsumowania.

Przedział ma rozdzielczość 1 s. Dopuszczamy różnicę między raportowanym timerem
a sumą przedziałów do 1 s na przedział. Okno sesji jest wyznaczane przez
raportowany start i elapsed. Może objąć rzeczywisty znacznik stop późniejszy
o co najwyżej sekundę, bez zmiany znacznika i raportowanych sum. Większe
rozbieżności z podanym znacznikiem końca lub nakładające się okna uniemożliwiają
wiarygodne przypisanie przedziałów. Brak pary zdarzeń nie zastępuje się początkiem
ani końcem podsumowania.

| Przypadek | Elapsed | Timer | Przedziały |
| --- | ---: | ---: | --- |
| Ciągły | 130 051 ms | 130 051 ms | 130 000 ms |
| Pauza | 196 565 ms | 133 360 ms | 61 000 ms i 72 000 ms, 64 000 ms pauzy |

Wyniki to sesja kompletna, podsumowanie z przyczyną braku przedziałów albo
nieważne podsumowanie dla danej sesji. Błędy na poziomie pliku rozróżniają
uszkodzenie, nieobsługiwany format, niespójność metadanych i przekroczenie
limitów. Błąd strumienia i anulowanie propagują się osobno. Niepełny wynik
z wiarygodnymi sumami nie może zwiększać statystyk do uzupełnienia przedziałów.

Budżet wynosi 20 plików, 8 MiB na plik, 32 MiB na partię i 100 wiadomości
`Session`. Plik ma limit 500 000 rekordów (definicje i dane) i 16 384
potencjalnych zdarzeń timera. Jeden wpis mieści do 4 096 przedziałów.
Bufor odczytu ma 8 KiB. Rozliczane są faktycznie pobrane bajty, także przy
błędzie. Na granicy limitu bieżący plik może sprawdzić EOF jednym odczytem.
Jeśli odczyt pobierze dodatkowy bajt, plik przekracza limit. Po wyczerpaniu
budżetu partii kolejne strumienie są odrzucane przed pierwszym odczytem.
Anulowanie jest sprawdzane podczas odczytu i rekonstrukcji. Przerwanie
zablokowanego wywołania dostawcy dokumentów będzie częścią integracji SAF.

## Weryfikacja

Fixture'y w `app/src/test/resources/fit` są syntetyczne. Generator to
`python3 scripts/generate-fit-fixtures.py`. Niezależne sprawdzenie wykonano
przez `python3 scripts/verify-fit-fixtures.py` z dostępnym na ścieżce Pythona
pakietem `fitdecode==0.11.0`. Potwierdzono pięć plików, CRC, typ Activity,
sumy i timestampy sesji, kolejność zdarzeń oraz wartość pola developerskiego
w skompresowanym rekordzie. Pakiet nie jest zależnością aplikacji ani CI.

JVM sprawdza wyniki, błędy, limity, anulowanie, zgodność z modelem aktywności
oraz deterministyczne mutacje. Cztery pliki przechodzą wszystkie skrócenia
i 2 000 prób mutacji, z czego połowa ma przeliczone CRC. Zmodyfikowany plik o
poprawnym formacie może zostać zaakceptowany, ale jego digest i kompletne
przedziały muszą przejść te same kontrole.

Pomiar lokalny na i7-6820HQ, JDK 21, JVM z `-Xmx64m`. Po czterech próbach
rozgrzewki wykonano 15 odczytów pliku w osobnym procesie testowym. Czasy
obejmują otwarcie, odczyt, CRC, SHA i rekonstrukcję. Alokacje mierzy
`ThreadMXBean` dla wątku odczytu. To pomiar JVM, bez SAF i bez Androida.

| Dane syntetyczne | Bajty | Mediana / najwolniejsza próba | Mediana alokacji na odczyt |
| --- | ---: | ---: | ---: |
| Aktywność ciągła | 111 | 0,64 / 2,14 ms | 14 816 B |
| Aktywność z 80 000 pomijanych rekordów | 8 000 120 | 235 / 315 ms | 14 840 B |
| 16 384 zdarzenia i 4 096 przedziałów | 114 785 | 13,3 / 18,3 ms | 6 707 904 B |

W najbardziej gęstej próbie maksymalny przyrost raportowanych szczytów pul
sterty wyniósł ok. 7,0 MiB. To obserwacja JVM, bez gwarancji identycznego
wyniku na urządzeniu.

## Warunki przed etapem B

- Klucz importu składa się z SHA-256 pliku i numeru wiadomości `Session`.
  Unikatowy indeks obejmie rekordy aktywne i usunięte. Powtórny import
  usuniętego wpisu pokaże jego status. Świadome przywrócenie ma reaktywować
  ten sam UUID w transakcji, zachowując klucz, dane pochodzenia i przedziały
  oraz aktualizując czas modyfikacji. Wymiana bajtów pliku nie jest podstawą
  automatycznego przywrócenia.
- Sekcja `FIT1` kopii v3 obejmie powiązanie z UUID i metadane pochodzenia,
  także dla znaczników usunięcia. Reader zachowa wcześniejsze warianty v3
  i oba etapy odzyskiwania z istniejących dzienników. Klucz i pochodzenie
  będą niezmienne podczas zwykłej edycji wpisu.
- Potrzebna jest wspólna kontrola pojemności niezależna od importu FIT.
  Proponuję jedno rozliczenie kosztu pełnej migawki przez komponent kopii:
  wstępne liczniki w SQL, a ostateczny limit według strumienia z tego samego
  enkodera, który przygotowuje eksport i dziennik. Nowe operacje użytkownika
  zwiększające rozmiar (import, ręczne wpisy, presety, korekty i sample)
  sprawdzają zmianę w kolejce zapisu, przed zatwierdzeniem. Wkład starszych
  danych i znaczników usunięcia też podlega rozliczeniu. Redukowanie danych
  musi pozostać możliwe blisko limitu oraz ponad nim.
- Zapis odtworzeń ma pierwszeństwo. Nie wolno zatrzymać go, gdy historia
  przekroczy pojemność obecnej kopii. Propozycja dla etapu B to najpierw
  oddzielić limit pamięci od rozmiaru archiwum. Przygotowywać i walidować
  pełne migawki w prywatnych plikach tymczasowych, a odczyt rozkładać na
  porcje, zachowując atomowe znaczniki dziennika. Ustalić wspólny limit
  rozmiaru dla eksporterów i czytników na podstawie pomiarów dwóch migawek
  i wolnego miejsca. Przed publikacją sprawdzić pełny eksport i przywrócenie
  dla baz już przekraczających dzisiejszy limit 16 MiB. Jeśli pomiary
  wykażą, że v3 nie jest w stanie bezpiecznie przenieść takich danych,
  decyzję o formacie i limitach uzgodnić przed włączeniem importu. Samo
  zwiększenie limitu obecnych buforów pamięci jest niewystarczające.
