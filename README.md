# OpenMetronome

Metronom na Androida dla muzyków, którzy chcą ćwiczyć tempo i własne poczucie
pulsu. Darmowy, otwartoźródłowy i całkowicie offline. Bez reklam, subskrypcji,
kont i Google Play Services.

## Pobierz i zacznij ćwiczyć

**OpenMetronome 1.1.0 — wydanie stabilne, versionCode 20.**

### [Pobierz APK 1.1.0](https://github.com/Franek2009/OpenMetronome/releases/download/v1.1.0/OpenMetronome-1.1.0.apk)

[Opis wydania i wyniki testów](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.1.0)
· [Sumy SHA-256](https://github.com/Franek2009/OpenMetronome/releases/download/v1.1.0/SHA256SUMS)
· [Fingerprint certyfikatu](https://github.com/Franek2009/OpenMetronome/releases/download/v1.1.0/SIGNING-CERT-SHA256.txt)

Wymagany Android **8.0 lub nowszy**. Interfejs jest po polsku; obsługuje
telefony i tablety w pionie oraz poziomie.

1. Pobierz APK i otwórz plik. Jeżeli Android poprosi, zezwól przeglądarce
   lub menedżerowi plików na instalowanie aplikacji z tego źródła.
2. Zainstaluj aplikację na istniejącej oficjalnej wersji, bez odinstalowywania.
3. Ustaw tempo i naciśnij **Start**.

Zachowujemy pakiet `org.openmetronome` i dotychczasowy podpis.
**APK debug lub z innych źródeł mogą mieć inny certyfikat.** Jeśli Android
odrzuca aktualizację, nie usuwaj aplikacji ani jej danych w celu obejścia
niezgodności — [podpis i aktualizacje](docs/RELEASING.md).
Przed aktualizacją warto zachować własną kopię `.ombak`.

## Nowości 1.1

- **Pełny ekran:** duże BPM, puls i takt, blokada dotyku z dostępnym Stop,
  opcjonalne utrzymywanie ekranu i ograniczenie migania. Przejście bez restartu sesji.
- **Procenty tempa:** 100/75/50/25% niezależnie od bazy 20–300 BPM;
  wynik 5–300 BPM. Rampa zmienia bazę. Zmiana tempa od następnego taktu
  albo następnego pulsu, który silnik może jeszcze bezpiecznie przeplanować.
- **Trening:** odliczanie 0/1/2 takty poza czasem praktyki, dodatkowe metra
  5/4, 7/4, 5/8 i 7/8 oraz grupowanie akcentów 5/8 i 7/8.
- **Statystyki 2.0:** cały czas ćwiczeń dzisiaj, Podsumowanie / Historia /
  Analiza, kalendarz, filtry i szczegóły sesji. Konfigurowalne progi domyślne
  20 s/sesję i 5 min/aktywny dzień nie usuwają krótkich sesji z historii.
  Nieznany przebieg dawnych sesji nie jest przedstawiany jako zero.
- **Trzy dodatkowe brzmienia:** Naturalny, Suchy i Naturalny niski, każde
  z osobnym akcentem. Sześć dotychczasowych brzmień zachowano bez zmian.
- Krótsze podpisy w interfejsie; instrukcje pozostają w dokumentacji.

[Ćwiczenie i pełny ekran](docs/PRACTICE-1.1.md)
· [Statystyki i zgodność danych](docs/STATISTICS-2.md)
· [Pochodzenie sampli i licencje](assets/samples/PROVENANCE.md)
· [Changelog](CHANGELOG.md)

## Co potrafi?

- Zmiana tempa przyciskami, suwakiem, wpisywaniem BPM i tap tempo.
- Metrum, akcenty, podziały rytmiczne i wizualny puls.
- Trening pulsu: losowa cisza, całe ciche takty i rosnąca trudność.
- Automatyczne zwiększanie i zmniejszanie tempa.
- Dziewięć brzmień, import własnych sampli WAV/MP3/OGG, regulacja głośności
  i opcjonalne wibracje.
- Odtwarzanie w tle i zatrzymywanie z powiadomienia.
- Presety, lokalna historia, serie i statystyki ćwiczeń.
- Eksport CSV oraz kopie ustawień, presetów, historii i własnych sampli `.ombak`.

W **6/8 BPM oznacza ósemki** — sześć głównych pulsów na takt.
CSV służy do analizy; do przywracania danych używaj `.ombak`.

[Instrukcja użytkowania](docs/USAGE.md)
· [Brzmienia i import własnych sampli](docs/CUSTOM-SAMPLES.md)

## W przygotowaniu: widgety 1.2

Mini Metronom, większy panel Start/Stop z procentami tempa i widget statystyk
z czasem ćwiczeń dzisiaj. Wszystkie korzystają ze wspólnego stanu aplikacji,
bez animowanego pulsu ani osobnego zegara audio.

**To funkcja rozwojowa — APK 1.1.0 wskazany powyżej nie zawiera widgetów.**
[Obsługa, rozmiary i ograniczenia](docs/WIDGETS.md).

## Kopie i zgodność

1.1 importuje `.ombak` v1 i v2; domyślnie eksportuje v2. Starsze 1.0 nie
odczyta v2. Osobny eksport zgodny v1 ostrzega o pominięciach i odrzuca
nowe metra lub barwy, których 1.0 nie obsługuje. Nie podmieniamy ich na inne.
Nie zakładamy możliwości cofnięcia instalacji do niższego versionCode.

## Jak wygląda?

| Telefon | Tablet |
| --- | --- |
| ![Metronom na telefonie](docs/images/phone.png) | ![Metronom na tablecie](docs/images/tablet.png) |

Podglądy pochodzą z 1.0.0. W 1.1 ekran główny ma układ hybrydowy
oraz osobny pełny ekran; ilustracje nie przedstawiają wszystkich nowych funkcji.

## Weryfikacja i ograniczenia

Regresja obejmuje JVM, PCM offline, Lint, buildy, ciche testy API26/35,
migracje, backup/restore i aktualizacje z porównaniem danych.
Dokładne wyniki kontroli oficjalnego APK i jego sumy znajdują się
w [opisie wydania](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.1.0)
i [raporcie 1.1](docs/V1.1-RELEASE-VALIDATION.md).

Użytkownik potwierdził prawidłową aktualizację i import statystyk oraz
zaakceptował odsłuch prototypów trzech barw. To nie jest automatyczne
potwierdzenie wszystkich scenariuszy M6 finalnego APK 1.1.
Historyczny odbiór RC2 na Samsungu A56 opisano w
[checkliście M6](docs/M6-ACCEPTANCE.md); nie przypisujemy go nowej wersji.

**Jitter i latencja fizycznego wyjścia pozostają niezmierzone.** Testy PCM
nie są pomiarem głośnika. Pełny dysk może uniemożliwić zapis najnowszych danych;
nie gwarantujemy działania na wszystkich urządzeniach i trasach audio.

Znalazłeś problem? [Zgłoś go na GitHubie](https://github.com/Franek2009/OpenMetronome/issues),
podając wersję aplikacji, model urządzenia, Androida i kroki odtworzenia.
Nie przesyłaj kluczy podpisu ani prywatnych danych.

## OpenMetronome DEV

DEV jest osobnym narzędziem dla deweloperów, z odrębnym pakietem, danymi,
podpisem i oznaczoną ikoną. Nie zastępuje zwykłej aplikacji.
Wydanie 1.1.0 obejmuje wyłącznie zwykły APK.

**Pełna rejestracja sesji DEV jest wyłączona** po niezaliczonej kwalifikacji
narzutu API26. Wynik FAIL pozostaje jawny; nie obiecujemy raportu nowej sesji.
Kod i dotychczasowa beta DEV pozostają dostępne dla zainteresowanych:
[stan diagnostyki](docs/DEV-DIAGNOSTICS.md), [warianty i build](docs/BUILD-VARIANTS.md),
[historyczna beta.1](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.1.0-beta.1).
Dodatkowe brzmienia, w tym osobna barwa „Drewniany”, nie są warunkiem wydania 1.1.

## Prywatność i współpraca

Dane pozostają na urządzeniu. Nie ma analityki, reklam ani komunikacji
z naszym serwerem; eksport uruchamiasz samodzielnie. [Prywatność](PRIVACY.md).

Projekt w Kotlinie i Jetpack Compose, na licencji [MIT](LICENSE).
Dystrybucja przez GitHub Releases; Google Play i F-Droid nie są obecnie kanałami projektu.

[Budowanie i testy](docs/BUILDING.md)
· [Architektura](docs/ARCHITECTURE.md)
· [Mapa kodu](docs/CODEBASE_MAP.md)
· [Roadmapa](docs/ROADMAP.md)
· [Proces wydawniczy](docs/RELEASING.md)
