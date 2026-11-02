# OpenMetronome

Metronom na Androida dla muzyków, którzy chcą ćwiczyć nie tylko tempo,
ale też własne poczucie pulsu. Darmowy, otwartoźródłowy i całkowicie offline.
Bez reklam, subskrypcji, kont i Google Play Services.

## Pobierz i zacznij ćwiczyć

[Pobierz APK — 1.0.0-rc.2](https://github.com/Franek2009/OpenMetronome/releases/download/v1.0.0-rc.2/OpenMetronome-1.0.0-rc.2.apk)

[Opis wydania](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.0.0-rc.2)
· [Sumy SHA-256](https://github.com/Franek2009/OpenMetronome/releases/download/v1.0.0-rc.2/SHA256SUMS)
· [Certyfikat podpisu](https://github.com/Franek2009/OpenMetronome/releases/download/v1.0.0-rc.2/SIGNING-CERT-SHA256.txt)

**1.0.0-rc.2 jest opublikowanym Release Candidate (Prerelease).**
To kandydat do pierwszego stabilnego wydania; **stabilne 1.0.0 nie zostało
jeszcze wydane**.

[Raport przygotowania RC2](docs/V1.0-RC2-VALIDATION.md).

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

## Co zmienia RC?

RC2 wprowadza zaakceptowane nowe logo: ikonę adaptacyjną, monochromatyczną
Androida 13+ oraz klasyczne PNG. Nie dodaje funkcji ani brzmień; sześć
dotychczasowych barw pozostaje bez zmian. „Drewniany” jest planowany na 1.0.1.

Zachowuje poprawki stabilności i ochrony danych z RC1:

- Niezawodniejsze kończenie sesji i obsługa błędów uruchamiania odtwarzania.
- Ponawianie nieudanych zapisów i odzyskiwanie utrwalonych zapisów sesji
  po restarcie aplikacji.
- Zachowanie zgodności kopii `.ombak` v1 oraz odbudowa niespójnych statystyk
  dziennych na podstawie sesji.

RC zachowuje dotychczasowe funkcje metronomu i treningu.
[Pełny opis zmian i wyniki weryfikacji wydania](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.0.0-rc.2).

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

## Status RC i zgłaszanie błędów

**Końcowy odbiór M6 i fizyczne testy audio nie są jeszcze w pełni zakończone.**
Testy automatyczne i dotychczasowe odsłuchy nie zastępują pełnego praktycznego
odbioru tego RC. Fizyczna precyzja wyjścia audio, w tym jitter, pozostaje
niezmierzona. Opublikowanie Prerelease nie oznacza ukończenia M6 ani wydania
stabilnego 1.0.0.

[Checklista odbioru M6](docs/M6-ACCEPTANCE.md)
· [Raport stabilizacji i oficjalnego RC1](docs/V1.0-RC1-VALIDATION.md)
· [Przygotowania RC2 i kryteria stabilnego 1.0](docs/V1.0-RC2-READINESS.md)

Aktualizacja oficjalnej beta.3 do RC1 na Samsungu A56 zachowała dane według
manualnego potwierdzenia użytkownika. Nie jest to pełny odbiór RC2 ani pomiar
fizycznej precyzji audio. Stabilne 1.0.0 nadal wymaga praktycznego odbioru
końcowego RC i osobnej zgody na publikację.

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
