# Zatwierdzony zakres po 1.0.0 — 1.1 beta

Ostatnia zatwierdzona iteracja łączy funkcje użytkowe w **1.1.0-beta.1**, zamiast
odkładać Statystyki 2.0 do 1.2 i publikować osobno samą kartę Dzisiaj.
Użytkownik zatwierdził implementację, małe PR i po testach publikację beta z dwoma
APK: PROD i DEV. Stable 1.1 wymaga oddzielnego odbioru i decyzji. Opublikowane
1.0.0 i jego tag/załączniki pozostają niezmienne. Bez operacji na telefonach,
fizycznego audio ani wibracji; bez usuwania danych.

## Zakres beta.1

- Pełny ekran, blokada z dostępnym Stop, opcjonalne utrzymywanie ekranu i redukcja migania.
- Hybrydowy UI; baza 20–300, procenty 100/75/50/25%, wynik 5–300 BPM.
- Rampa zmienia bazę; procent nie resetuje treningu. Domyślnie zmiana od taktu,
  opcjonalnie od następnego niewyrenderowanego pulsu bez przesuwania jego terminu.
- Odliczanie 0/1/2 takty poza czasem praktyki; nowe metra i grupy, bez zmiany 6/8.
- Statystyki: Dzisiaj, Podsumowanie / Historia / Analiza; domyślne progi 20 s/5 min,
  wszystkie sesje zachowane, historia konfiguracji nowych sesji, brak wymyślonych danych starych.
- Migracja Room 2→3, `.ombak` v2, import v1 i zgodny eksport v1 z jawnymi pominięciami.
- DEV: izolowane pakiety/dane, oznaczona ikona, osobny trwały certyfikat,
  przygotowana rejestracja i ZIP. Pełna rejestracja sesji pozostaje wyłączona
  w beta na decyzję użytkownika po FAIL budżetu narzutu API26.
- Usunięcie sześciu dawnych testów sprzętowego audio; ochrona i testy offline pozostają.

Wdrożenie podzielono na PR #17/18 (ikona DEV/Dzisiaj), #19 (ćwiczenie/UI),
#20 (statystyki/dane) i #21 (przygotowanie pełnej diagnostyki bez aktywacji beta). Kolejny PR obejmuje
metadane beta i dwie osobne ścieżki podpisania. Szczegóły:
[ćwiczenie](PRACTICE-1.1.md), [statystyki](STATISTICS-2.md),
[diagnostyka](DEV-DIAGNOSTICS.md), [warianty](BUILD-VARIANTS.md).

## Bramki

Każdy końcowy HEAD: JVM, Lint, PCM, wszystkie APK i ciche API26/35,
aktualizacje na kluczu testowym oraz kontrola izolacji. DEV przed przyszłą aktywacją:
pełny tor ART offline, identyczny PCM, brak strat w standardowym scenariuszu,
p99 ścieżki renderowania do +10%, jawne granice pomiaru pamięci.

Przed publiczną beta: dokładny tag na zielonym main, osobno podpisane PROD/DEV,
kontrola podpisu i zawartości, oficjalne 1.0→beta na API26/35, instalacja/aktualizacja
DEV bez współdzielenia danych, tożsamość plików draftu i artefaktów. Publikacja
Prerelease nie zmienia 1.0.0 jako latest stable. Po publikacji kontrola anonimowego
pobierania i osobny PR z linkami README. Manualny odbiór beta należy do użytkownika.

## Poza beta.1

„Drewniany” nadal planowany osobno po dostarczeniu oryginalnych WAV i kwalifikacji;
brak sampla nie blokuje pozostałych funkcji. Bez stroika, kamertonu, chmury, PDF,
edytora planów, nieuzasadnionego NDK/Oboe i aktualizacji zależności.
Jitter oraz latencja fizycznego wyjścia pozostają niezmierzone.

Kwalifikacja DEV ma osobny workflow dispatch API26/35 i niezmieniony próg +10%.
Nie jest bramką beta z wyłączoną rejestracją; jej wcześniejsze FAIL pozostają
udokumentowane. Regresja beta wymaga testu pustych hooków i raportuje kwalifikację
jako NOT RUN, nie PASS. Nie usuwamy benchmarku ani nie uruchamiamy fizycznego audio.


## Domknięcie 1.1 — aktualna decyzja użytkownika

Odstąpiono od odświeżania UI i nowej wizualizacji. Obecny wygląd zostaje.
Trzy warianty Druminfected zaakceptowano odsłuchowo; kolejne CC0 VCSL
przygotowano do osobnego wyboru. Nazwa „Drewniany” nie jest wymaganiem.
Pełne DEV pozostaje przed bramką +10%, bez zmiany kryterium.
Aktualizacja i import statystyk manualnie potwierdzone przez użytkownika,
bez dopisywania wersji, urządzenia lub niezgłoszonych scenariuszy.


Aktywacja DEV w gałęzi rozwojowej: po 5/5 CI 42427681439 i 2/2 kwalifikacji
42427680223 dla 059e47f. To nie zmienia opublikowanej beta.1. Końcowy HEAD
musi przejść własne bramki, bez podnoszenia +10% lub ukrywania FAIL.
