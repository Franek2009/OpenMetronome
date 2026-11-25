# Statystyki muzyczne w 1.3

`AppData.observeMusicStatistics` przekazuje `MusicStatisticsSummary` przyszłym
ekranom i widgetom. Wywołujący przekazuje chwilę obliczenia jako `Flow<Instant>`.
Moduł reaguje na zmiany danych i ustawień. Podczas przywracania zgłasza
`Loading`; błąd odczytu lub wymagane odzyskiwanie daje `Unavailable`.
`Ready` z zerowym czasem oznacza poprawnie odczytaną pustą historię.

## Zakres i obliczenia

- Statystyki metronomu nadal używają dotychczasowych progów, stref zapisanych
  przy sesjach i przebiegów konfiguracji.
- Łączna aktywność używa wszystkich dodatnich, utrwalonych czasów odtwarzania,
  także poniżej 20 s i utrwalonych checkpointów. Koniec odtwarzania wynika
  z początku i czasu PCM. Uwzględniamy przedziały nieusuniętych aktywności.
  Podsumowania bez przedziałów i pauzy nie zwiększają wyniku. Obliczenia
  używają przedziałów odtworzonych z FIT po walidacji założeń z fundamentu.
- Przedziały są półotwarte. Najpierw liczymy unię na osi UTC, następnie
  przypisujemy jej części do lokalnych dni w wybranej strefie statystyk.
  Nakładka kilku źródeł zwiększa czas tylko raz. Część po chwili obliczenia
  pozostaje poza wynikiem. Podział uwzględnia północ oraz zmiany przesunięcia
  strefy, więc zależy od rzeczywistej długości dnia, również przy DST.
- Przed pierwszą konfiguracją proponowana jest bieżąca strefa urządzenia.
  Zapis ustawień utrwala wybór. Podróż i zmiana strefy telefonu nie
  przesuwają zapisanych dni. Zmiana strefy statystyk przelicza całą historię
  według nowego kalendarza.
- Dzień jest aktywny od 5 minut włącznie. Ustawienie 0 zalicza każdy dzień
  z dodatnią aktywnością. Domyślny cel to 5 aktywnych dni w tygodniu od
  poniedziałku. Można go zmienić na 1–7 albo wyłączyć. Po wyłączeniu celu
  wystarcza co najmniej jeden aktywny dzień, żeby zaliczyć tydzień do serii.
- Seria dzienna liczy kolejne aktywne dni, a tygodniowa kolejne tygodnie z
  osiągniętym celem. Otwarty dzień lub tydzień może kontynuować bieżącą
  serię po jej ostatnim zaliczonym okresie. Po przerwie przez pełny okres
  bieżąca seria wynosi zero. Zmiana progu lub celu przelicza historię.
- Średnia na dzień kalendarzowy dzieli łączny czas przez liczbę dat od
  pierwszej zarejestrowanej aktywności do dzisiaj włącznie, także dat bez
  aktywności. Przy cofnięciu lokalnej daty zakres obejmuje ostatnią już
  zarejestrowaną datę. Średnia na aktywny dzień używa czasu z aktywnych dni
  i ich liczby. Brak mianownika daje brak średniej.

Room dostarcza lekką projekcję przedziałów z jednego zapytania. Obliczenia
po stronie aplikacji wykonują jeden przebieg po uporządkowanych przedziałach
i przechowują dzienne sumy. Przerwy między okresami nie tworzą pustych wpisów.
Test JVM obejmuje 5 500 dni i 66 000 przedziałów. Cicha instrumentacja obejmuje
edycję, usunięcie, błędny rekord i błąd zapytania oraz niezmieniony próg
statystyk metronomu.

## Kopie i odzyskiwanie

Pełne `.ombak` v3 i migawki dziennika zachowują strefę, próg i cel wraz z
informacją o jego wyłączeniu. Import kopii bez tych pól zachowuje aktualne
ustawienia muzyczne. Przed przywróceniem zapisujemy przyjętą konfigurację w
migawce docelowej, żeby ponowienie i cofnięcie dawały ten sam efekt po
restarcie. Eksporty zgodności v1/v2 pomijają nowe pola. Room zachowuje schemat 4.

## Otwarte przed importerem FIT

- Obsłużyć raportowany czas timera nieznacznie przekraczający okno
  wyznaczone przez sekundowe znaczniki zdarzeń. Zachować raportowaną wartość
  i dokładność znaczników, bez dopisywania czasu do przedziałów.
- Zapewnić pełny backup, przywracanie i bezpieczny rollback dużej historii
  przed dopuszczeniem masowego importu. Zweryfikować obecne limity archiwum
  oraz narzut pamięci i dysku przy zapisywaniu dwóch migawek.
