# OpenMetronome

Metronom na Androida dla muzyków, którzy chcą ćwiczyć nie tylko tempo,
ale też własne poczucie pulsu. Darmowy, otwartoźródłowy i całkowicie offline.
Bez reklam, subskrypcji, kont i Google Play Services.

## Pobierz i zacznij ćwiczyć

**Nowość do testów: 1.1.0-beta.1 — Prerelease, nie wydanie stabilne.**

- [Pobierz zwykły APK — beta](https://github.com/Franek2009/OpenMetronome/releases/download/v1.1.0-beta.1/OpenMetronome-1.1.0-beta.1.apk) — aktualizacja oficjalnego 1.0.0.
- [Pobierz APK DEV — beta](https://github.com/Franek2009/OpenMetronome/releases/download/v1.1.0-beta.1/OpenMetronome-DEV-1.1.0-beta.1.apk) — osobna aplikacja, dane i ikona DEV.

[Opis beta i wyniki testów](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.1.0-beta.1)
· [Sumy SHA-256](https://github.com/Franek2009/OpenMetronome/releases/download/v1.1.0-beta.1/SHA256SUMS)
· [Certyfikat PROD](https://github.com/Franek2009/OpenMetronome/releases/download/v1.1.0-beta.1/SIGNING-CERT-SHA256.txt)
· [Certyfikat DEV](https://github.com/Franek2009/OpenMetronome/releases/download/v1.1.0-beta.1/DEV-SIGNING-CERT-SHA256.txt)

**Najnowsze stabilne wydanie pozostaje 1.0.0:**
[pobierz APK stabilny](https://github.com/Franek2009/OpenMetronome/releases/download/v1.0.0/OpenMetronome-1.0.0.apk)
· [opis](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.0.0)
· [sumy](https://github.com/Franek2009/OpenMetronome/releases/download/v1.0.0/SHA256SUMS).
Jego APK i tag nie zostały zmienione.

Beta dodaje pełny ekran, procenty tempa, odliczanie, nowe metra i Statystyki 2.0
z całym czasem ćwiczeń dzisiaj. **Pełna rejestracja sesji DEV jest wyłączona**:
nie zaliczyła kwalifikacji narzutu API26. DEV ma zwykłe funkcje, własną historię
oraz przygotowaną obsługę istniejących raportów ZIP. Nie obiecujemy raportu nowej sesji.
[Zakres ćwiczenia](docs/PRACTICE-1.1.md) · [Statystyki i kopie](docs/STATISTICS-2.md) ·
[Stan DEV](docs/DEV-DIAGNOSTICS.md) · [Odbiór beta](docs/V1.1-BETA1-VALIDATION.md).

Wymagany Android **8.0 lub nowszy**. Aplikacja obsługuje telefony i tablety
w pionie i poziomie. Interfejs jest po polsku.

1. Pobierz APK z linku powyżej lub z sekcji Assets na stronie wydania.
2. Otwórz plik. Jeżeli Android poprosi, zezwól użytej przeglądarce lub menedżerowi
   plików na instalowanie aplikacji z tego źródła.
3. Zainstaluj aplikację, ustaw tempo i naciśnij **Start**.

Zwykły APK zachowuje dotychczasowy podpis. DEV ma osobny, trwały certyfikat
i pakiet; nie zastępuje zwykłej aplikacji ani nie kopiuje jej danych. Aktualizację
każdego z nich instalujesz
na istniejącą aplikację, bez odinstalowywania, pod warunkiem zgodności podpisów.
**Wcześniejsze APK debug mogą mieć inny podpis.** Jeśli Android odrzuca
aktualizację, nie odinstalowuj aplikacji ani nie czyść jej danych, aby obejść
niezgodność — [szczegóły podpisywania i aktualizacji](docs/RELEASING.md).

## Nowości beta 1.1

- Pełny ekran: duże BPM, puls i takt, blokada dotyku z dostępnym Stop,
  opcjonalne utrzymywanie ekranu i ograniczenie migania. Bez restartu sesji.
- Baza 20–300 BPM oraz 100/75/50/25%; dokładny wynik 5–300 BPM.
  Rampa zmienia bazę. Zmiana tempa od taktu lub następnego możliwego pulsu.
- Odliczanie 0/1/2 takty poza czasem praktyki, metra 5/4, 7/4, 5/8 i 7/8,
  grupowanie 5/8 i 7/8. Dotychczasowe znaczenie BPM w 6/8 zachowane.
- Statystyki: Dzisiaj, Podsumowanie / Historia / Analiza, kalendarz i filtry.
  Progi domyślne 20 s/sesję i 5 min/aktywny dzień nie usuwają krótkich sesji.
  Przebieg nieznanych starych sesji nie jest przedstawiany jako zero.
- Import `.ombak` v1/v2, domyślny eksport v2 i osobny eksport zgodny z 1.0
  z ostrzeżeniem pominięć. Stare 1.0 nie odczyta v2; przed aktualizacją zachowaj kopię v1.
  Nie zakładamy możliwości cofnięcia instalacji do niższego versionCode.

Wszystkie sześć dotychczasowych brzmień zachowano. „Drewniany” czeka na
oryginalne WAV-y i kwalifikację źródeł. Stabilne 1.1 wymaga praktycznego odbioru
beta i osobnej decyzji — [checklista użytkownika](docs/V1.1-BETA1-VALIDATION.md).

## Co potrafi?

- Baza tempa 20–300 BPM: przyciski, suwak, wpisywanie wartości i tap tempo;
  w beta mnożnik 100/75/50/25% i wynik od 5 BPM.
- Metrum, akcenty i podziały rytmiczne; w beta osiem metrów i grupowanie 5/8 oraz 7/8.
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

Powyższe podglądy pochodzą ze stabilnego 1.0.0; beta ma odświeżony układ
i pełny ekran. Interfejs dopasowuje się do telefonu i tabletu.

## Weryfikacja, ograniczenia i zgłaszanie błędów

Beta ma pełną regresję CI i kontrolę oficjalnie podpisanych APK, w tym
aktualizację 1.0.0→beta na emulatorach API26/35 oraz oddzielną instalację/reinstalację DEV.
[Wyniki, hashe i ograniczenia beta](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.1.0-beta.1).
Benchmark pełnej diagnostyki DEV pozostał niezaliczony i jest osobnym zestawem;
wyłączenie rejestracji jest sprawdzane testem, a kwalifikacja nie jest liczona jako PASS.
Praktyczny odbiór beta na fizycznym urządzeniu pozostaje do wykonania przez użytkownika.

Poniżej historyczny odbiór linii 1.0, który nie stanowi odbioru beta:

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

[Mapa kodu dla współpracy i audytu](docs/CODEBASE_MAP.md).
