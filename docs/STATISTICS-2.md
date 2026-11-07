# Statystyki 2.0 — implementacja rozwojowa 1.1

To opis modelu danych i funkcji linii 1.1 beta, nie APK 1.0.0.

- **Dzisiaj**: cały utrwalony czas, także krótkich sesji i planowanej ciszy.
  Odczyt checkpointów około co 5 s, bez dodawania drugiego timera.
- **Podsumowanie**: zaliczony czas dzisiaj, tydzień od poniedziałku, miesiąc,
  serie, średnia sesja i wykresy. Progi domyślne: 20 s/sesję i 5 min/dzień.
  Zmiana progów przelicza historię, nie usuwa sesji. Najpierw kwalifikujemy
  całą sesję, następnie dzielimy jej czas według zapisanej strefy i DST.
- **Historia**: kalendarz, filtr daty/statusu, szczegóły i kolejne strony po
  50 sesji. Nie ograniczamy obliczeń do ostatnich 100 rekordów.
- **Analiza**: zakresy wynikowego BPM, metra i czas z włączoną rampą lub
  treningiem ciszy. Ten ostatni nie oznacza zmierzonego czasu rzeczywistej
  losowej ciszy. Analiza używa tylko sesji z pełnym przebiegiem konfiguracji;
  czas bez pełnych danych jest pokazany oddzielnie, a nie jako zero.

## Zapis i granice

Silnik przekazuje zmiany rzeczywiście zastosowanej konfiguracji do jednego
ograniczonego kanału producent/konsument (512 pozycji). Nie zapisuje na dysk,
nie czeka i nie blokuje audio przy przeciążeniu. Recorder odczytuje wyłącznie
zmiany do pozycji odtworzonych ramek; przyszłe pozycje z bufora nie są historią.
Odliczanie jest wyłączone z czasu praktyki i przebiegu treningu.

Room 3 dodaje nullable `practice_sessions.timeline`. Migracja 2→3 nie dopisuje
starym sesjom fikcyjnych segmentów. Wewnętrzny format przebiegu jest wersjonowany,
ma limit 4096 zmian i 512 KiB danych binarnych na sesję. Przepełnienie kanału
lub limitu oznacza niepełny przebieg, ale nie zatrzymuje zapisu czasu sesji.
Statystyki i CSV odczytują przebiegi pojedynczo, bez ładowania całej historii
przebiegów do RAM. Eksport pełnej kopii sprawdza rozmiar rekordów przed
pobraniem ich treści. Zmiany i status kompletności trafiają do istniejącej trwałej kolejki razem
z sesją. Nie są osobną, nieskoordynowaną bazą diagnostyki DEV.

## Kopie i odzyskiwanie

Domyślny eksport `.ombak` v2 zachowuje przebiegi oraz niezależne preferencje
statystyk i ekranu. Importuje także v1. Kopia v1 nie zawiera tych nowych
preferencji; ich import pozostawia bieżące wartości. Import v2 i rollback
odzyskują konfigurację, preferencje, rekordy i dokładne bajty sampli.
Dziennik undo/redo zawiera tę samą pełną migawkę, także przy przerwaniu procesu.

Osobny eksport zgodny z 1.0 (v1) wymaga potwierdzenia pominięć: brak przebiegu,
progów i ustawień ekranu, a 1.0 ignoruje procenty i odliczanie. Eksport v1
odmawia przy nowych metrach, zamiast podstawiać inne metrum lub usuwać dane.
Limit archiwum pozostaje 16 MiB; przekroczenie kończy się jawnym błędem,
nie komunikatem sukcesu niepełnej kopii. Przygotowanie i walidacja poprzedzają
otwarcie docelowego dokumentu.

Regresja obejmuje stare fixture v1, v2, migrację Room, współbieżny kanał,
kwalifikację sesji, północ/DST, granice odtworzenia, rollback i rzeczywiste
zabicie odrębnego testowego procesu. Wyniki konkretnego SHA będą w raporcie
wydania; sam kod testu nie oznacza PASS. Bez testów na telefonach i bez audio.
