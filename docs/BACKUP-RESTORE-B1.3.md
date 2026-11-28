# B1.3 — dyskowe migawki restore i dziennik v2

Punkt wyjścia to `70411b8`. Eksport, limity publiczne oraz odczyt kopii
v1/v2/v3 korzystają z architektury B1.2. [Raport B1.2](BACKUP-STREAMING-B1.2.md)

## Migawki

- Po sprawdzeniu ZIP-a strumieniowy walidator zapisuje znormalizowane
  odtworzenia i dniówki oraz pozostałe rekordy do prywatnej migawki
  `after`. Dniówki dostarczone w kopii nadal podlegają walidacji.
  Walidator sprawdza też referencje i unikalność. Nie przekazuje częściowo
  zdekodowanej bazy do stosowania danych.
- Migawka `before` odczytuje dokładne kolumny ze spójnej transakcji Room.
  Zapytania widzą stan bazy wraz ze zmianami widocznymi przez WAL. Daty,
  flagi, historyczne dniówki, notatki, tombstones i nieprzezroczyste pola
  pozostają takie jak w bazie. Ustawienia zapisuje serializer DataStore,
  a blokada sampli obejmuje odczyt oryginalnych WAV-ów. Nie obowiązuje
  publiczny limit bajtów ani liczby rekordów dla `before`.
- Prywatny format w każdej roli zawiera `records.db`, `preferences.pb`
  i `samples/`. Tabele zawierają wyłącznie jawne projekcje Room 5. Wzorzec
  wyboru kolumn jest częścią formatu dziennika i musi być zachowany przy
  przyszłej migracji. Przygotowanie sprawdza strukturę i integralność bazy,
  ustawienia oraz komplet kanonicznych sampli.
- Rekordy przechodzą przez ograniczone okna kursora SQLite. Przenoszenie
  do żywej bazy utrzymuje w Kotlinie jeden rekord i kontroluje do 2 MiB
  tekstu UTF-8 na rekord. Publiczny dekoder materializuje co najwyżej
  przedziały jednej aktywności. Przygotowanie biblioteki audio mieści
  się w istniejącym limicie 32 WAV-ów.

## Protokół

1. `preparing-v2/` przejmuje źródło, buduje obie migawki i rozlicza wolne
   miejsce. Binarny manifest z wersją i UUID operacji wiąże role, nazwy,
   rozmiary i SHA-256 wszystkich plików. Obie bazy są zamknięte i nie
   potrzebują roboczego WAL-a ani SQLite rollback journal. Dane i ich
   katalogi przechodzą fsync.
2. `READY` zawiera UUID i SHA-256 manifestu. Marker powstaje przez zapis,
   fsync i atomową podmianę. Dopiero atomowe przeniesienie
   `preparing-v2/` do `active/` i fsync katalogu nadrzędnego dopuszczają
   zmiany żywych danych. Przerwaną, nieopublikowaną próbę można usunąć.
   Brak `READY` w opublikowanym dzienniku v2 jest błędem integralności.
3. Biblioteka sampli stosuje dane z migawki. Jedna transakcja Room
   podmienia wszystkie tabele, a DataStore stosuje odpowiadające
   ustawienia. Gdy wynik lokalnego COMMIT jest niepewny, dziennik
   pozostaje do odtworzenia. Po zapisaniu bazowego pliku SQLite, WAL,
   ustawień, sampli i ich katalogów aplikacja trwale publikuje
   `COMMITTED`, związany z tym samym UUID i hashem manifestu.
4. Recovery weryfikuje oba zbiory. `READY` bez `COMMITTED` wybiera
   `before`, a spójny `COMMITTED` wybiera `after`. Przed ponownym
   stosowaniem usuwa wyłącznie stały katalog roboczy, który można
   odtworzyć z migawki. Te same dane można bezpiecznie zastosować
   ponownie po kolejnej śmierci procesu. Po fsync żywych danych
   aplikacja atomowo wycofuje cały dziennik i sprząta wycofane pliki.
5. Nieprawidłowy manifest, marker, migawka albo błąd trwałego
   stosowania zachowują materiały. Stan `recoveryRequired`
   blokuje odtwarzanie i zapisy do kolejnej próby po restarcie.
   Dzienniki v1 nadal wybierają stary zapis `.ombak` zgodnie
   z ich dotychczasowymi markerami.

## Miejsce i ENOSPC

Przygotowanie zajmuje miejsce na skopiowany ZIP, indeks walidacji i
migawkę po, a następnie na obie migawki. Błąd w fazie przygotowania
sprząta własne pliki. Po zakończeniu tej fazy staging ZIP-a i indeks
są już zwolnione. Wstępny budżet wolnego miejsca pokrywa osobno
stosowanie i rollback. Każda składowa to trzy razy suma bieżącej
wielkości SQLite z WAL-em oraz docelowej migawki danej fazy, z
marginesem 4 MiB na małe pliki, katalogi i metadane. To konserwatywne
oszacowanie, które trzeba kalibrować pomiarami. Przy planowaniu
pojemności należy doliczyć pliki, które już istnieją w momencie
sprawdzenia dostępnego miejsca.

Rollback otrzymuje oddzielną rezerwę przez `posix_fallocate`. Kontrola
uwzględnia rzeczywistą liczbę zaalokowanych bloków. Jeżeli system plików
odrzuci alokację, restore kończy się przed `READY`. Rezerwa jest
zwalniana przed rollbackiem lub ponowną próbą recovery. Po jej usunięciu
dziennik nadal zawiera obie migawki. Inny proces może zająć odzyskane
miejsce, więc nieudana próba pozostawia dziennik i blokadę mutacji.
Kolejny restart korzysta ponownie z tych samych migawek.

## Pomiary

[Benchmark B1.1](BACKUP-BASELINE-B1.1.md) pozostaje w historii wraz z
raportem. Aktualny harness porównuje SHA-256 wszystkich kolumn rekordów
w ustalonej kolejności, ustawienia i bajty sampli. Po odmowie publicznego
eksportu test uruchamia małe poprawne restore i odtwarza fixture przed
kolejnym pomiarem. Tryb `BACKUP_BASELINE_EXPERIMENT=true` dodatkowo
wykonuje pełny round-trip w jawnej testowej obwiedni B1.2.
