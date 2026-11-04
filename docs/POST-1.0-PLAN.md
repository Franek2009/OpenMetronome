# Zatwierdzony plan po 1.0.0

Plan przyjęty przez użytkownika; realizacja przez małe PR z testami i przeglądem.
Akceptacja planu nie oznacza zgody na merge, tagi, oficjalne klucze i publikację.
Stabilne wydanie 1.0.0 pozostaje niezmienne. Nie wykonujemy operacji na telefonach,
audio ani wibracji bez nowej zgody; nie usuwamy danych.

## 1.1.0

1. Warianty prod/dev i debug/release: osobne pakiety i magazyny, osobny trwały
   klucz DEV, ochrona oficjalnego pakowania. [Konfiguracja](BUILD-VARIANTS.md).
2. Pełna diagnostyka DEV: wszystkie pulsy, podziały i zapisy AudioTrack;
   events/beat/audio CSV oraz metadata JSON w ZIP. Ograniczone bufory 4 MiB,
   limit 512 MiB/sesję i 2 GiB łącznie, bez automatycznego usuwania raportów.
   Asynchroniczny zapis, jawne straty, brak blokowania audio przy błędach dysku.
3. Pełny ekran: kropki, BPM, puls/takt, Start/Stop; ta sama sesja i silnik.
   Blokada edycji pozostawia Stop, opcje utrzymywania ekranu i redukcji animacji.
4. Hybrydowy UI: tempo wynikowe i baza, szybkie sterowanie, rozwijany trening,
   dostęp do presetów/metrum/brzmień; układy telefonu/tabletu, pion/poziom.
5. Procenty: baza 20–300, 100/75/50/25%, dokładny wynik 5–300 BPM. Tap zmienia
   bazę; mnożnik w ustawieniach/presetach, brak pola = 100%. Rampa zmienia bazę,
   zmiana procentu nie resetuje treningu. W 1.1 stosowanie na granicy taktu.
   Zachowujemy `.ombak` v1; aplikacja 1.0 ignoruje mnożnik, co jawnie opisujemy.

Powyższe obszary są kolejnymi PR, nie jednym dużym refaktorem. Narzuty DEV
mierzymy porównawczo; PCM musi pozostać identyczny, standardowy scenariusz bez
strat, wstępny budżet wzrostu p99 renderowania 10%. Przekroczenie wymaga
optymalizacji lub jawnej decyzji, nie ukrycia wyników.

## 1.2.0

- Zmiana tempa od najbliższego niewyrenderowanego pulsu: zachowujemy jego termin,
  nowe tempo określa odstęp po nim; domyślnie pozostaje następny takt.
- Statystyki 2.0: Podsumowanie, Historia, Analiza. Progi domyślnie **20 s**/sesję,
  **5 min**/dzień; retrospektywne, bez usuwania sesji. Sesję kwalifikujemy jako
  całość, jej czas dzielimy według zapisanej strefy i północy/DST.
- Segmenty efektywnej konfiguracji nowych sesji w zwykłej aplikacji,
  niezależnie od DEV; stare sesje bez wymyślonego przebiegu. Migracja Room,
  spójne checkpointy i odzyskiwanie. Import v1/v2, pełny eksport v2 i zgodny
  v1 z ostrzeżeniem o pominięciach; nieobsługiwane stare metra bez podstawień.
- Odliczanie 0/1/2 takty poza czasem praktyki; trening zaczyna się po nim,
  bez restartu AudioTrack. Cykliczna cisza już istnieje, poprawiamy jej UI.
- Metra 5/4, 7/4, 5/8, 7/8; BPM nadal puls ćwierćnutowy/ósemkowy.
  Grupy 5/8: 2+3, 3+2; 7/8: 2+2+3, 2+3+2, 3+2+2.
  Nie zmieniamy znaczenia starych presetów 6/8 ani identyfikatorów brzmień.

## Niezależnie i bramki

„Drewniany” możliwy w 1.0.1 po dostarczeniu i kwalifikacji oryginałów oraz
osobnym odsłuchu użytkownika; nie blokuje 1.1/1.2. Bez stroika, kamertonu,
chmury, PDF i edytora planów. Bez nieuzasadnionego NDK/Oboe lub frameworków.

Każdy PR: JVM, odpowiednie PCM/Lint/buildy, raport PASS/FAIL/SKIPPED i audyt.
Przed wydaniem pełna cicha regresja API26/35, migracje/backup i aktualizacje;
oficjalny podpisany kandydat i aktualizacje oficjalny→oficjalny osobno.
Metryki wewnętrzne nigdy nie są pomiarem fizycznego jittera/latencji.
