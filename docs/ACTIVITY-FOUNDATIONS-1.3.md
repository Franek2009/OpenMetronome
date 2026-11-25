# Fundament danych aktywności muzycznych w 1.3

Ten etap dodaje model i zapis aktywności oraz kopię v3. Interfejs obsługi
aktywności, grupowanie, statystyki muzyczne i widgety powstaną w kolejnych
etapach. Import FIT, automatyczna synchronizacja oraz USB OTG na Androidzie
pozostają poza tym etapem.

| Tabela Room 4 | Dane |
| --- | --- |
| `music_activities` | UUID, kategoria, źródło, początek i koniec okna w milisekundach UTC, strefa IANA, czas timera, rozdzielczość przedziałów, opcjonalny instrument, czasy utworzenia, edycji i usunięcia |
| `music_activity_intervals` | UUID rodzica, początek i koniec przedziału; klucz złożony z UUID rodzica i początku, klucz obcy z usuwaniem kaskadowym |

Migracja 3→4 tworzy tylko nowe tabele i indeks. Ścieżki 1→2→3→4 oraz 2→3→4
zachowują istniejące dane. Nowe tabele są puste po migracji. Odtworzenia
metronomu nadal mają te same identyfikatory, format i mechanizm zapisu.

Przedziały są uporządkowane, leżą w oknie wpisu i mogą się stykać. Nakładanie
przedziałów wewnątrz jednego wpisu jest odrzucane. Niezależne aktywności mogą
się nakładać. Ręczny wpis wymaga przedziału, a jego timer odpowiada sumie
zapisanych przedziałów. Źródło FIT można już reprezentować jako podsumowanie
bez przedziałów albo serię przedziałów z jawną rozdzielczością. W syntetycznym
przykładzie timer 133 360 ms i przedziały 61 oraz 72 s pozostają oddzielnymi
wartościami. Okno wpisu obejmuje zapisane przedziały. Dokładne umiejscowienie
pauz nie wynika z samego czasu timera. W tym etapie nie ma dekodera FIT.

Zapis i edycja obejmują rekord i przedziały w jednej transakcji. Edycja
zachowuje UUID, pochodzenie i czas utworzenia. Usunięcie oznacza rekord datą
usunięcia, wyklucza go z listy aktywnych i blokuje przypadkowe wskrzeszenie
przez edycję. Pełna kopia zawiera także oznaczone rekordy.

## Kopie i odzyskiwanie

Zwykły eksport tworzy `.ombak` v3. Metadane v3 to dane v2 oraz lista aktywności
z przedziałami. Enkoder weryfikuje gotowe archiwum przed otwarciem pliku
docelowego. Starsze wersje importują się jak dotąd i nie zawierają aktywności
muzycznych. Przywracanie zastępuje zestaw danych, więc aplikacja ostrzega
także przed utratą obecnych wpisów przy wyborze kopii v1/v2. Jawna opcja v2
zachowuje zapis metronomu z przebiegiem konfiguracji i ustawieniami.
Opcja v1 ma dotychczasowe dodatkowe ograniczenia. Oba starsze eksporty
pomijają aktywności muzyczne.

Migawki „przed” i „po” dziennika przywracania obejmują nowe rekordy i przedziały.
Przed znacznikiem zatwierdzenia odzyskujemy poprzedni stan, po nim nowy. Starszy
dziennik v2 nadal da się odczytać. Kolejka zaległych odtworzeń nadal zapisuje
migawki v2 i odzyskuje je po odzyskaniu ewentualnej kopii. Eksport czeka na
ponowienie zaległych zapisów.

## Próby do audytu

- JVM: walidacja przedziałów, osobny czas timera i rozdzielczość, kopie v3 i
  zgodności v1/v2, odtworzenie dziennika v2, testy kolejki zaległych zapisów.
- Instrumentacja: migracje z Room 1/2/3, zachowanie danych metronomu, wymuszony
  błąd zapisu przedziału podczas edycji oraz trwałość oznaczenia usunięcia.
- Instrumentacja w oddzielnym procesie: przerwanie przywracania i ponowne
  przerwanie samego odzyskiwania. Porównanie starych i nowych migawek obejmuje
  również aktywności, przedziały, usunięcia oraz bajty sampli.
- Regresja CI: porównanie eksportów z aktualizacji rozpoznaje format v3 i
  wykrywa zmianę aktywności. Pełne Android CI uruchamia ciche testy API 26/35.

Raporty z emulatorów są osobną bramką obok migracji i backupu. Próba aktualizacji
oficjalnie podpisanych APK 1.2.1 → 1.3 należy do weryfikacji wydania.
