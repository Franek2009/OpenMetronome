# Fundament danych aktywności muzycznych w 1.3

Ten etap dodaje model i zapis aktywności oraz kopię v3. Interfejs obsługi
aktywności, grupowanie, statystyki muzyczne i widgety powstaną w kolejnych
etapach. Import FIT, automatyczna synchronizacja oraz USB OTG na Androidzie
pozostają poza tym etapem.

| Tabela Room 4 | Dane |
| --- | --- |
| `music_activities` | UUID, kategoria, źródło, początek i koniec okna w milisekundach UTC, strefa IANA, czas timera, rozdzielczość przedziałów, opcjonalny instrument i notatka, czasy utworzenia, edycji i usunięcia |
| `music_activity_intervals` | UUID rodzica, początek i koniec przedziału; klucz złożony z UUID rodzica i początku, klucz obcy z usuwaniem kaskadowym |

Migracja 3→4 tworzy tylko nowe tabele i indeks. Ścieżki 1→2→3→4 oraz 2→3→4
zachowują istniejące dane. Nowe tabele są puste po migracji. Odtworzenia
metronomu nadal mają te same identyfikatory, format i mechanizm zapisu.

Przedziały są uporządkowane, leżą w oknie wpisu i mogą się stykać. Nakładanie
przedziałów wewnątrz jednego wpisu jest odrzucane. Niezależne aktywności mogą
się nakładać. Ręczny wpis wymaga przedziału, a jego timer odpowiada sumie
zapisanych przedziałów. Źródło FIT można już reprezentować jako podsumowanie
bez przedziałów albo serię przedziałów z jawną rozdzielczością. Dla przedziałów
odtworzonych z tego samego zegara każda para początku i końca może wnieść do
różnicy do jednej jednostki rozdzielczości. Dopuszczalna bezwzględna różnica
timera i sumy to liczba przedziałów × ich rozdzielczość. Syntetyczny przykład
133 360 ms i przedziały 61 oraz 72 s przy rozdzielczości 1 s mieści się w
limicie 2 s. Zapis zachowuje obie wartości. Niezgodność ponad limit powoduje
odrzucenie wpisu, także podczas czytania kopii z poprawnym CRC. Przyszły
importer może jawnie zachować takie nagranie jako samo podsumowanie po
weryfikacji pozostałych danych. Podsumowania czekają na uzupełnienie
przedziałów i pozostają poza łącznymi statystykami muzycznymi.

Zapis i edycja obejmują rekord i przedziały w jednej transakcji. Edycja
zachowuje UUID, pochodzenie i czas utworzenia. Usunięcie oznacza rekord datą
usunięcia, wyklucza go z listy aktywnych i blokuje przypadkowe wskrzeszenie
przez edycję. Pełna kopia zawiera także oznaczone rekordy. Opcjonalna notatka
może zawierać wiele wierszy i ma limit 2 000 jednostek UTF-16. Pusty wpis
wprowadzony ręcznie zapisuje się bez notatki.

## Kopie i odzyskiwanie

Zwykły eksport tworzy `.ombak` v3. Metadane v3 to dane v2, lista aktywności
z przedziałami i opcjonalna końcowa sekcja par UUID–notatka. Pierwsze testowe
kopie v3 kończyły się po liście aktywności i nadal się wczytują. Starszy
czytnik testowy wymaga aktualizacji do odczytu kopii z notatkami. Enkoder
weryfikuje gotowe archiwum przed otwarciem pliku docelowego. Import v1/v2
działa jak dotąd. Te wersje nie zawierają aktywności
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

## Granice rozmiaru

Limit 16 MiB rozpakowanej zawartości obejmuje wszystkie metadane i WAV-y.
Osobne limity to 10 000 rekordów aktywności, 65 536 przedziałów na kopię,
4 096 przedziałów na wpis i 6 000 bajtów UTF-8 na notatkę. Import rozlicza
limit przedziałów przed utworzeniem kolejnej listy. Pełny eksport i migawka
przywracania sprawdzają liczniki i rozmiar metadanych aktywności w SQL przed
pobraniem rekordów. Końcowa walidacja obejmuje całe archiwum. Po przekroczeniu
limitu pełna kopia lub przywrócenie kończą się błędem przed zmianą danych.
Jawny eksport v1/v2 może nadal zapisać dane metronomu i nie ładuje aktywności.
Limity dotyczą kopii. Przed udostępnieniem importowania hurtowego trzeba
zweryfikować pojemność historii i narzut dwóch migawek przy przywracaniu.

## Kolejne zmiany formatu

Room 4 z tego etapu nadal jest schematem przedpremierowym. Notatka wchodzi
do migracji 3→4. CI używa izolowanych baz. Jeśli wcześniejszy build Room 4
został zainstalowany na urządzeniu testowym, przed zmianą trzeba wykonać
pełny eksport w tym buildzie. Dla jednorazowych instalacji z danymi
przygotowujemy osobną instalację testową z końcowym schematem i sprawdzamy
import kopii, zachowując starą instalację jako punkt powrotu. Android Room
nie otworzy bazy o tym samym numerze i innym schemacie. Jeśli wcześniejszy
schemat trafił do instalacji, które mają otrzymać aktualizację w miejscu,
następny PR musi zachować stary schemat oraz dodać migrację do nowego numeru.
Po udostępnieniu pierwszego buildu do aktualizacji z zachowaniem danych
zamrażamy jego schemat. Kolejne zmiany wprowadzamy migracją bez czyszczenia.

Testowe kopie v3 i dzienniki z każdego używanego układu są fixture'ami
zgodności. Czytnik po zmianie nadal powinien odtwarzać starsze układy.
Przed udostępnieniem v3 zamrażamy znaczenie pól, list i kodowania. Zmianę
niekompatybilną po udostępnieniu identyfikuje nowy numer formatu. Migracja
Room i format kopii są wersjonowane osobno i odbierane razem.

## Plan klucza deduplikacji FIT

Pierwszy importer obliczy lokalnie SHA-256 oryginalnego strumienia FIT i
zapisze klucz z prefiksem `fit-sha256:v1:`. Klucz będzie unikatowy w całej
bazie, także dla znaczników usunięcia. Będzie odrębny od UUID, niezależny od
nazwy pliku, kategorii, instrumentu, notatki i ręcznej korekty przedziałów.
Edycja i usunięcie zachowają klucz, a ponowny import tego samego pliku nie
wskrzesi usuniętego wpisu. Pełna kopia i dziennik będą przenosić klucz wraz
z oznaczonym rekordem. Kontrola i zapis muszą być atomowe, również przy
równoczesnych importach. Wpisy ręczne i archiwa sprzed importera nie będą
miały tego klucza.

Klucz rozpoznaje identyczne pliki, również po zmianie nazwy. Powtórny eksport
tej samej aktywności może mieć inne bajty. Zbieżne czasy mogą być wskazówką do
ręcznego potwierdzenia. `sport=generic` i `sub_sport=generic` nie identyfikują
ćwiczeń muzycznych. Plan nie wymaga przechowywania surowych FIT, GPS, danych
zdrowotnych ani identyfikatorów zegarka. Testy importera obejmą zmianę nazwy,
równoległy import, edycję, usunięcie, ponowny import i pełne przywrócenie.

## Próby do audytu

- JVM: walidacja przedziałów, osobny czas timera i rozdzielczość, kopie v3 i
  zgodności v1/v2, odtworzenie dziennika v2, testy kolejki zaległych zapisów.
- Instrumentacja: migracje z Room 1/2/3, zachowanie danych metronomu, wymuszony
  błąd zapisu przedziału podczas edycji oraz trwałość oznaczenia usunięcia.
  Po błędzie zapisu i ponownym otwarciu bazy eksport v3 zawiera zarówno
  odzyskane odtworzenie, jak i wcześniej zapisaną aktywność.
- Instrumentacja w oddzielnym procesie: przerwanie przywracania i ponowne
  przerwanie samego odzyskiwania. Porównanie starych i nowych migawek obejmuje
  również aktywności, przedziały, usunięcia oraz bajty sampli.
- Regresja CI: porównanie eksportów z aktualizacji rozpoznaje format v3 i
  wykrywa zmianę aktywności. Pełne Android CI uruchamia ciche testy API 26/35.

Raporty z emulatorów są osobną bramką obok migracji i backupu. Próba aktualizacji
oficjalnie podpisanych APK 1.2.1 → 1.3 należy do weryfikacji wydania.
