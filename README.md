# OpenMetronome

Metronom na Androida dla muzyków, którzy chcą ćwiczyć nie tylko tempo,
ale też własne poczucie pulsu. Darmowy, otwartoźródłowy i całkowicie offline.
Bez reklam, subskrypcji, kont i Google Play Services.

## Pobierz i zacznij ćwiczyć

[Pobierz APK — 1.0.0](https://github.com/Franek2009/OpenMetronome/releases/download/v1.0.0/OpenMetronome-1.0.0.apk)

[Opis wydania](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.0.0)
· [Sumy SHA-256](https://github.com/Franek2009/OpenMetronome/releases/download/v1.0.0/SHA256SUMS)
· [Certyfikat podpisu](https://github.com/Franek2009/OpenMetronome/releases/download/v1.0.0/SIGNING-CERT-SHA256.txt)

**1.0.0 jest pierwszym opublikowanym wydaniem stabilnym.**
Zachowuje funkcjonalność RC2, dotychczasowy podpis i zgodność danych.
„Drewniany” pozostaje niezależnym planem na 1.0.1.
Przygotowujemy **1.1.0-beta.1 (Prerelease)** z pełnym ekranem, procentami tempa,
odliczaniem, nowymi metrami i Statystykami 2.0, w tym całym czasem ćwiczeń dzisiaj.
Osobny wariant **OpenMetronome DEV** ma własne dane, ikonę z oznaczeniem DEV
oraz pełne lokalne raporty diagnostyczne ZIP. Te funkcje nie należą do APK 1.0.0.
[Zakres i zgodność](docs/PRACTICE-1.1.md) · [Statystyki](docs/STATISTICS-2.md) ·
[Diagnostyka DEV](docs/DEV-DIAGNOSTICS.md) · [Plan](docs/POST-1.0-PLAN.md).
Linki pobierania beta pojawią się po weryfikacji i publicznej publikacji.

Wymagany Android **8.0 lub nowszy**. Aplikacja obsługuje telefony i tablety
w pionie i poziomie. Interfejs jest po polsku.

1. Pobierz APK z linku powyżej lub z sekcji Assets na stronie wydania.
2. Otwórz plik. Jeżeli Android poprosi, zezwól użytej przeglądarce lub menedżerowi
   plików na instalowanie aplikacji z tego źródła.
3. Zainstaluj aplikację, ustaw tempo i naciśnij **Start**.

Oficjalne APK z GitHuba mają stały podpis cyfrowy. Aktualizację instalujesz
na istniejącą aplikację, bez odinstalowywania, pod warunkiem zgodności podpisów.
**Wcześniejsze APK debug mogą mieć inny podpis.** Jeśli Android odrzuca
aktualizację, nie odinstalowuj aplikacji ani nie czyść jej danych, aby obejść
niezgodność — [szczegóły podpisywania i aktualizacji](docs/RELEASING.md).

## Wydanie 1.0.0

Wersja 1.0.0 opiera się na zaakceptowanym RC2: to samo oficjalne logo
z ikoną adaptacyjną, monochromatyczną Androida 13+ i klasycznymi PNG.
Sześć dotychczasowych brzmień i funkcje metronomu pozostają bez zmian.

Zachowuje poprawki stabilności i ochrony danych z RC1:

- Niezawodniejsze kończenie sesji i obsługa błędów uruchamiania odtwarzania.
- Ponawianie nieudanych zapisów i odzyskiwanie utrwalonych zapisów sesji
  po restarcie aplikacji.
- Zachowanie zgodności kopii `.ombak` v1 oraz odbudowa niespójnych statystyk
  dziennych na podstawie sesji.

1.0.0 zachowuje dotychczasowe funkcje metronomu i treningu.
[Pełny opis zmian i wyniki weryfikacji wydania](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.0.0).

## Co potrafi?

- Tempo 20–300 BPM: przyciski, suwak, wpisywanie wartości i tap tempo.
- Metrum 2/4, 3/4, 4/4 i 6/8, akcenty oraz podziały rytmiczne.
- Trening pulsu: losowa cisza, całe ciche takty i stopniowo rosnąca trudność.
- Automatyczne zwiększanie i zmniejszanie tempa.
- Sześć wybranych brzmień kliku oraz import własnych sampli WAV/MP3/OGG,
  regulacja głośności, wizualny puls i opcjonalne wibracje.
- Odtwarzanie w tle oraz zatrzymywanie z powiadomienia.
- Presety, lokalna historia ćwiczeń, serie i wykresy statystyk.
- Eksport historii do CSV oraz pełna kopia ustawień, presetów, historii
  i własnych sampli w formacie `.ombak`.

W 6/8 BPM oznacza **ósemki — sześć głównych pulsów na takt**.
CSV służy do analizy historii; do przywracania danych służy kopia `.ombak`.

- [Ustawienia, trening, statystyki i kopie zapasowe](docs/USAGE.md)
- [Brzmienia i import własnych sampli](docs/CUSTOM-SAMPLES.md)

## Jak wygląda?

| Telefon | Tablet |
| --- | --- |
| ![Ekran metronomu na telefonie](docs/images/phone.png) | ![Ekran metronomu na tablecie](docs/images/tablet.png) |

Ciemny interfejs dopasowuje się do ekranu telefonu i tabletu.

## Weryfikacja, ograniczenia i zgłaszanie błędów

Oficjalnie podpisany APK 1.0.0 przeszedł kontrolę podpisu, sum i metadanych.
Zaliczono sześć aktualizacji oryginalnych oficjalnych APK beta.3/RC1/RC2→1.0
na API 26/35, z porównaniem testowych danych i dokładnych bajtów sampla.
[Pełne wyniki i ograniczenia wydania](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.0.0).

Użytkownik manualnie potwierdził na Samsungu Galaxy A56 działanie oficjalnego
RC2: aktualizację, uruchomienie i logo, dłuższą praktykę z blokadą, Stop
z powiadomienia, historię/ustawienia/zapis, akcenty/trening i wibracje.
Potwierdził też prawidłowy ostatni test przerwania audio, bez podania szczegółów.
Nie twierdzimy, że osobno sprawdzono wszystkie warianty focus/słuchawek;
nie oznaczamy całego M6 jako PASS ani nie przypisujemy tego odbioru do
fizycznego odsłuchu nowego APK 1.0.0.

**Fizyczny jitter i latencja pozostają niezmierzone.** Automatyczne pominięcia
audio nie są PASS. Pełny dysk może uniemożliwić utrwalenie najnowszych danych;
nie gwarantujemy wszystkich urządzeń i tras audio.
[Dokładny zakres odbioru M6](docs/M6-ACCEPTANCE.md) ·
[Dokumentacja przygotowania 1.0](docs/V1.0-VALIDATION.md).

Znalazłeś problem? [Zgłoś go na GitHubie](https://github.com/Franek2009/OpenMetronome/issues),
podając wersję aplikacji, model urządzenia, wersję Androida i kroki odtworzenia.
Nie przesyłaj kluczy podpisu ani prywatnych danych.

## Prywatność

Ustawienia, presety, historia i własne sample pozostają na urządzeniu.
Nie ma analityki, reklam ani komunikacji z naszym serwerem. Eksport uruchamiasz
samodzielnie. [Informacja o prywatności](PRIVACY.md).

## Kod i współpraca

Projekt w Kotlinie i Jetpack Compose, na licencji [MIT](LICENSE).
Można budować z terminala na Linuxie, bez Android Studio.

- [Budowanie i testy](docs/BUILDING.md)
- [Architektura](docs/ARCHITECTURE.md) i [roadmapa](docs/ROADMAP.md)
- [Zmiany w wersjach](CHANGELOG.md) i [przygotowanie wydania](docs/RELEASING.md)

Aplikacja jest dystrybuowana przez GitHub Releases. Google Play i F-Droid
nie są obecnie kanałami publikacji projektu.
