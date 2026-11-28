# B1.2 — strumieniowy eksport i walidacja kopii

Pomiar odniesienia można odtworzyć również z commita `af80607`.

## Przepływ danych

- Eksport zachowuje publiczny format v3 oraz dotychczasowe częściowe eksporty
  v1/v2. Kolejka zapisów domyka zaległe odtworzenia, a transakcja Room i blokada
  biblioteki sampli utrzymują spójność odczytu. Zapytania kursorem przesuwają się
  według klucza. Porcja obejmuje do 256 wierszy i 2 MiB rozliczonego ładunku.
  Enkoder trzyma przedziały co najwyżej jednej aktywności i bajty jednego WAV-a.
- Pierwsza kontrola wykonuje agregaty SQL na żądanie. Enkoder rozlicza dokładne
  bajty przed kompresją oraz bajty gotowego ZIP-a. Limity publiczne pozostają
  odpowiednio 16 i 17 MiB, a limity liczby elementów pozostają bez zmian.
  Przy odmowie dane pozostają w bazie. Zapis sesji nie wykonuje tej kontroli.
- Gotowy prywatny ZIP przechodzi pełną walidację przed wywołaniem SAF. Wybór
  dokumentu i zapis z pliku do dostawcy przebiegają już po zwolnieniu rezerwacji
  danych. Anulowanie wyboru usuwa kopię roboczą. Błąd dostawcy może zostawić
  niepełny plik docelowy i jest zgłaszany jako błąd eksportu.
- Import najpierw kopiuje strumień źródłowy do ograniczonego pliku roboczego.
  Walidator odczytuje katalog ZIP, CRC i ograniczone strumienie wpisów. Przetwarza
  metadane rekord po rekordzie. Indeks tymczasowy SQLite sprawdza unikalność
  oraz referencje, m.in. dziennych wierszy i notatek. Stare odwołania w historii
  do usuniętych sampli oraz osierocone kotwice grupowania zachowują dotychczasową
  semantykę. Publiczny import nadal odtwarza dniówki oraz normalizuje `checkpointAt`.
- Po strumieniowej walidacji import przekazuje lokalny plik istniejącemu
  odczytowi i mechanizmowi restore. Ten etap nadal materializuje migawki w RAM.
  `RestoreJournal`, stosowanie danych i dokładny rollback zachowują istniejący
  przebieg. To ograniczenie obowiązuje także przy importowaniu dużych historii.
- Pliki eksportu i walidacji są poza dziennikiem restore. Sprzątanie usuwa
  wyłącznie pliki swojego zadania oraz jego pozostałości po restarcie procesu.
  Gotowy ZIP ma charakter roboczy, więc po przerwaniu eksport trzeba ponowić.

## Odtwarzanie pomiarów

Użyj tych samych syntetycznych scenariuszy i procedury co w [B1.1](BACKUP-BASELINE-B1.1.md).
W standardowej pracy benchmark sprawdza zachowanie limitów produkcyjnych,
pełny round-trip dla dopuszczonych historii i trwały zapis kolejnego odtworzenia.
Raport JSON podaje osobne granice przygotowania, kodowania, walidacji i dostawy
oraz czas zwolnienia rezerwacji danych. Walidacja osobna od eksportu pozostaje
porównawczym odczytem starszego dekodera. Zapis docelowy mierzy zwykły plik na
emulatorze. Opóźnienie dostawcy SAF sprawdza osobna regresja z wstrzymanym zapisem.

`BACKUP_BASELINE_EXPERIMENT=true` dodaje oddzielny pomiar kodowania i walidacji
po przebiegu z limitami publicznymi. Jawna obwiednia testowa dopuszcza 256 MiB
rozwiniętych danych i 257 MiB ZIP-a oraz 150 tys. wierszy na kolekcję,
30 tys. aktywności i 120 tys. przedziałów. Eksperyment zapisuje wyłącznie w
katalogu testu i waliduje wynik. Żaden duży ZIP z eksperymentu nie trafia do
produkcyjnego importu ani mechanizmu odzyskiwania.

## Wyniki

### Eksport 10 tys. odtworzeń z prostego profilu

Czasy w sekundach. Rezerwacja i restore podają pary B1.1 / B1.2. Próba
docelowa mierzy od otwarcia pliku do jego zamknięcia. W czterech udanych
eksportach B1.2 Start był już dostępny przy otwieraniu celu. Restore zachował
dane i bajty sampli, a rezerwacja pozostała aktywna na granicy `PREPARED`.

| API | Ekran | Przygotowanie | Kodowanie | Walidacja | Cel | Rezerwacja | Restore |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 26 | bez UI | 0.273 | 5.472 | 4.462 | 0.001 | 6.177 / 10.207 | 12.629 / 16.376 |
| 26 | Statystyki | 0.209 | 3.629 | 2.943 | 0.001 | 5.823 / 6.781 | 12.829 / 18.114 |
| 35 | bez UI | 0.856 | 13.942 | 7.836 | 0.002 | 7.456 / 22.635 | 16.430 / 40.461 |
| 35 | Statystyki | 0.531 | 9.730 | 4.643 | 0.001 | 6.748 / 14.905 | 16.628 / 29.153 |

Maksima procesu i plików w MiB. Sterta i PSS eksportu podają B1.1 / B1.2.

| API | Ekran | Sterta eksportu | PSS eksportu | ZIP | ZIP + indeks SQLite | Sterta / PSS restore B1.2 |
| --- | --- | --- | --- | --- | --- | --- |
| 26 | bez UI | 38.87 / 5.07 | 101.57 / 64.32 | 0.28 | 0.88 | 49.52 / 112.28 |
| 26 | Statystyki | 56.64 / 27.30 | 133.88 / 102.25 | 0.28 | 0.93 | 69.45 / 145.84 |
| 35 | bez UI | 55.94 / 7.03 | 179.96 / 111.90 | 0.28 | 0.88 | 72.12 / 221.67 |
| 35 | Statystyki | 79.67 / 40.24 | 220.32 / 210.15 | 0.28 | 0.88 | 86.64 / 233.61 |

### Macierz z limitami publicznymi

Para oznacza próbę bez UI i z otwartymi Statystykami. `OK` oznacza eksport
i pełny restore. Przy odmowie poprawny mały plik źródłowy nie mógł być
zastosowany z powodu pojemności migawki bieżącej bazy. Mierzony zapis
odtworzenia po próbach powiódł się w obu przypadkach.

| API | Dane | Wynik | Fixture (s) | Rezerwacja eksportu (s) | Restore (s) | Baza (MiB) | Szczyt WAL (MiB) | Otwarcie statystyk (s) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 26 | 10 tys. / prosty | OK | 2.893 | 10.207 / 6.781 | 16.376 / 18.114 | 9.64 | 9.69 | 2.453 |
| 26 | 10 tys. / gęsty | Odmowa | 3.813 | 2.738 / 2.921 | 0.041 / 0.038 | 20.54 | 0.68 | 2.888 |
| 26 | 50 tys. / prosty | Odmowa | 8.089 | 0.057 / 0.075 | 0.058 / 0.130 | 47.85 | 0.64 | 5.515 |
| 26 | 50 tys. / gęsty | Odmowa | 8.984 | 0.080 / 0.080 | 0.072 / 0.159 | 102.34 | 0.68 | 4.602 |
| 26 | 100 tys. / prosty | Odmowa | 15.027 | 0.084 / 0.115 | 0.127 / 0.893 | 95.71 | 0.64 | 9.446 |
| 26 | 100 tys. / gęsty | Odmowa | 17.206 | 0.120 / 0.146 | 0.124 / 0.939 | 204.73 | 0.73 | 9.254 |
| 35 | 10 tys. / prosty | OK | 12.002 | 22.635 / 14.905 | 40.461 / 29.153 | 9.64 | 9.69 | 6.343 |
| 35 | 10 tys. / gęsty | Odmowa | 6.809 | 7.056 / 6.962 | 0.053 / 0.066 | 20.54 | 0.68 | 4.678 |
| 35 | 50 tys. / prosty | Odmowa | 24.956 | 0.083 / 0.117 | 0.083 / 0.633 | 47.85 | 0.64 | 8.726 |
| 35 | 50 tys. / gęsty | Odmowa | 32.395 | 0.116 / 0.324 | 0.143 / 0.322 | 102.34 | 0.68 | 10.088 |
| 35 | 100 tys. / prosty | Odmowa | 60.595 | 0.134 / 0.313 | 0.173 / 0.641 | 95.71 | 0.64 | 17.902 |
| 35 | 100 tys. / gęsty | Odmowa | 52.845 | 0.256 / 0.383 | 0.308 / 0.833 | 204.73 | 0.73 | 15.771 |

### Próby ponad limitem w obwiedni testowej

Czasy w sekundach. Sterta i PSS podają pierwszą próbkę i szczyt procesu
podczas kodowania i walidacji. Pierwsza próbka obejmuje stan procesu po
zakończeniu próby ze Statystykami. Eksperyment kończy się na walidacji.

| API | Dane | Przygotowanie | Kodowanie | Walidacja | ZIP MiB | Katalog roboczy MiB | Sterta MiB | PSS MiB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 26 | 50 tys. / prosty | 2.148 | 41.650 | 36.690 | 1.37 | 6.35 | 72.52 / 74.55 | 146.97 / 151.43 |
| 26 | 100 tys. / gęsty | 5.670 | 77.695 | 166.841 | 3.79 | 15.06 | 81.83 / 124.72 | 260.78 / 305.10 |
| 35 | 50 tys. / prosty | 2.302 | 45.199 | 24.787 | 1.37 | 6.39 | 57.36 / 77.81 | 237.94 / 250.92 |
| 35 | 100 tys. / gęsty | 6.682 | 91.936 | 122.551 | 3.79 | 15.07 | 70.96 / 139.59 | 251.85 / 364.28 |

### Wnioski

- W zestawie 12 scenariuszy cztery eksporty i restore zakończyły się pełnym
  odtworzeniem danych. Dwadzieścia eksportów i odpowiadających im prób restore
  odmówiło przy zachowaniu danych. Każda syntetyczna historia przyjęła kolejne
  odtworzenie. Gęsty profil 10 tys. został odrzucony przez dokładny licznik po
  rozpoczęciu kodowania. Agregat SQL jest szybką kontrolą dolnego oszacowania.
- Sterta i PSS eksportu spadły we wszystkich czterech pomiarach. Przybył koszt
  odczytu, walidacji i dyskowego indeksu. Kopiowanie do celu pozostaje poza
  rezerwacją, choć przygotowanie sprawdzonej kopii może trwać dłużej niż B1.1.
  Czas uruchomienia Statystyk i ponownego odczytu żywej bazy pozostaje osobnym
  zadaniem. Przy odmowie kopii gęstego profilu 100 tys. otwarte Statystyki
  podniosły obserwowany PSS z 60 do 258 MiB na API 26 i ze 105 do 317 MiB na
  API 35. Eksport obu prób odmówił w fazie przygotowania.
- Czasy pochodzą z pojedynczych prób na emulatorach debug. Obraz API 35
  pracował w trybie emulacji programowej. Macierz zebrała pomiary bezpośredniej
  dostawy do pliku przed ostatnim uszczelnieniem odczytu katalogu ZIP i bramki
  selektora SAF. Regresja końcowego kodu obejmuje te granice osobnymi testami.
- Pełny round-trip 100 tys. odtworzeń i docelowe limity wymagają B1.3. W tej
  części nadal obowiązują ograniczenia migawek restore, w tym pamięć potrzebna
  na stary zmaterializowany odczyt i miejsce na obie migawki oraz WAL.
  Kodowanie z walidacją gęstej próbki 100 tys. trwało 214–245 s. W połączeniu
  z kosztem Statystyk to argument za pozostawieniem limitów publicznych.
