# OpenMetronome

Metronom na Androida dla muzyków, którzy chcą ćwiczyć nie tylko tempo,
ale też własne poczucie pulsu. Darmowy, otwartoźródłowy i całkowicie offline.
Bez reklam, subskrypcji, kont i Google Play Services.

## Pobierz i zacznij ćwiczyć

[Pobierz APK — 0.13.0-beta.3](https://github.com/Franek2009/OpenMetronome/releases/download/v0.13.0-beta.3/OpenMetronome-0.13.0-beta.3.apk)

[Opis wydania i sumy kontrolne](https://github.com/Franek2009/OpenMetronome/releases/tag/v0.13.0-beta.3)

Wersja **0.13.0-beta.3** jest wydaniem testowym, nie ukończoną wersją 1.0.
Wymagany Android **8.0 lub nowszy**. Telefon i tablet są obsługiwane w pionie
i poziomie.

**0.13.0-beta.3** odzyskuje dane po przerwaniu przywracania kopii `.ombak`:
przed odblokowaniem Start i edycji odtwarza cały poprzedni albo zatwierdzony
nowy zestaw. Poprawia też kończenie edycji na Androidzie 8.
Kopia obejmuje ustawienia, presety, historię oraz własne sample. Dostępne są też
wykresy statystyk i serie dni ćwiczeń; CSV pozostaje raportem do analizy.
[Obsługa statystyk i kopii](docs/USAGE.md).

Nadal dostępne są wybrane brzmienia i import WAV/MP3/OGG z wersji 0.12.
Po aktualizacji z 0.8 zacznij od niższej głośności — poziom PCM jest wyższy.
[Brzmienia i import sampli](docs/CUSTOM-SAMPLES.md).

1. Otwórz stronę wydań na urządzeniu i pobierz plik `.apk` z sekcji Assets.
2. Otwórz plik. Jeżeli Android poprosi, zezwól użytej przeglądarce lub menedżerowi
   plików na instalowanie aplikacji z tego źródła.
3. Zainstaluj aplikację, ustaw tempo i naciśnij **Start**.

Aktualizacje pobierasz z tej samej strony i instalujesz na istniejącą aplikację.
Nie trzeba jej odinstalowywać. Oficjalne wydania mają stały podpis cyfrowy.
**Wcześniejsze APK debug mają inny podpis:** jeśli aktualizacja jest odrzucana,
nie usuwaj aplikacji, jeśli zależy Ci na jej danych — [szczegóły](docs/RELEASING.md).

## Przygotowanie 1.0

Trwa stabilizacja **1.0.0-rc.1**; kandydat nie jest jeszcze opublikowany.
Publiczne pobieranie powyżej nadal prowadzi do beta.3. Zakres obejmuje
niezawodność zapisu sesji i backupów, bez dodawania funkcji.
Użytkownik potwierdził około godzinną praktykę bez zauważonych problemów,
również z blokadą ekranu przez nieustalony czas, oraz poprawny własny sample.
Wersja użytego APK nie jest potwierdzona. Fizyczny jitter i underruny nie były
mierzone w tej sesji. [Odbiór i kryteria 1.0](docs/V1.0-RC1-VALIDATION.md).

## Co potrafi?

- Tempo 20–300 BPM: przyciski, suwak, wpisywanie wartości i tap tempo.
- Metrum 2/4, 3/4, 4/4 i 6/8, akcenty oraz podziały rytmiczne.
- Trening pulsu: losowa cisza, całe ciche takty i stopniowo rosnąca trudność.
- Automatyczne zwiększanie i zmniejszanie tempa.
- Sześć wybranych brzmień kliku oraz import własnych sampli,
  regulacja głośności, wizualny puls i opcjonalne wibracje.
- Odtwarzanie w tle oraz zatrzymywanie z powiadomienia.
- Presety, lokalna historia ćwiczeń, serie i wykresy statystyk, eksport do CSV oraz
  pełna kopia zapasowa ustawień, historii i własnych sampli.

W 6/8 BPM oznacza **ósemki — sześć głównych pulsów na takt**.
CSV to eksport historii do analizy; do przywracania danych służy kopia `.ombak`.
[Więcej o ustawieniach i treningu](docs/USAGE.md).

## Jak wygląda?

| Telefon | Tablet |
| --- | --- |
| ![Ekran metronomu na telefonie](docs/images/phone.png) | ![Ekran metronomu na tablecie](docs/images/tablet.png) |

Ciemny interfejs dopasowuje się do wielkości ekranu. Grafiki interfejsu są
własne; inspiracją układu były zrzuty Metronome Beats, nie kopiujemy ich do APK.

## Prywatność i status projektu

Ustawienia, presety i historia pozostają na urządzeniu. Nie ma analityki,
reklam ani komunikacji z naszym serwerem. Eksport uruchamiasz samodzielnie.
[Informacja o prywatności](PRIVACY.md).

Użytkownik potwierdził podstawowy dźwięk i około godzinną zwykłą praktykę.
Pełny odbiór sprzętowy oraz pomiary rzeczywistego wyjścia audio pozostają otwarte;
nie deklarujemy zakończenia stabilizacji. [Wyniki stabilizacji](docs/V1.0-RC1-VALIDATION.md).

Znalazłeś problem? [Zgłoś go na GitHubie](https://github.com/Franek2009/OpenMetronome/issues),
podając wersję aplikacji, model urządzenia, wersję Androida i kroki odtworzenia.
Nie przesyłaj kluczy podpisu ani prywatnych danych.

## Kod i współpraca

Projekt w Kotlinie i Jetpack Compose, na licencji [MIT](LICENSE).
Można budować z terminala na Linuxie, bez Android Studio.

- [Budowanie i testy](docs/BUILDING.md)
- [Architektura](docs/ARCHITECTURE.md) i [roadmapa](docs/ROADMAP.md)
- [Zmiany w wersjach](CHANGELOG.md) i [przygotowanie wydania](docs/RELEASING.md)

Pierwszym kanałem dystrybucji jest GitHub. Google Play i F-Droid nie są
jeszcze kanałami publikacji projektu.
