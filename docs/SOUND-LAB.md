# Laboratorium barw — 0.11.0-beta.1

Dokument historyczny. Od 0.12 lista jest wyselekcjonowana i ma zwykłe nazwy;
pozostałe identyfikatory służą zgodności presetów.
[Aktualne brzmienia i własne sample](CUSTOM-SAMPLES.md).

35 własnych eksperymentalnych barw plus niezmieniony bank ośmiu z 0.9 (43 łącznie).
To kandydaci do odsłuchu, nie deklaracja, że każdy brzmi głośniej na głośniku.
Domyślny dźwięk pozostaje Klasyczny. Numery i identyfikatory zapisujemy na stałe,
żeby późniejsze iteracje nie myliły ocen ani presetów.

## Druga seria: rodzina 06

Użytkownik wstępnie dopuścił 01/02/05/06, następnie wskazał 06 (ewentualnie 02),
a ostatecznie wybrał **06 — Impuls pełny** jako wzorzec kolejnych 15 barw.
Oryginalny 06 pozostaje niezmieniony, także jego akcent. Nie zmieniamy
automatycznie wybranej barwy, głośności ani zapisanych presetów.

Ustawienia → Dźwięk → **Rodzina 06 — 15 nowych wariantów**.
Pierwszy element listy to oryginalny 06, dalej numery 21–35. Można porównywać
je z wzorcem bez przechodzenia do starszego banku. Poprzednie 01–20 nadal
są dostępne w „Eksperymentalne — 20 barw”; nie usuwamy żadnych identyfikatorów.

Wspólna baza: ton i nieparzyste harmoniczne ×3/×5/×7, bez szumu,
podtrzymana obwiednia, łagodne nasycenie `tanh`, ten sam szczyt 29162.
06 ma 1050 Hz, długość 28 ms, podtrzymanie 9 ms, zanik 10 ms,
wagi harmonicznych 1/.33/.2/.14 oraz drive 1.8. Akcent podnosi wysokość o 22%.
Poniższa tabela podaje zmiany względem tego wzorca; pozostałe parametry są wspólne.

| Nr | Nazwa | Zmiany względem 06 |
| --- | --- | --- |
| 21 | Pełny niski 750 Hz | ton 750 Hz |
| 22 | Pełny niski 900 Hz | ton 900 Hz |
| 23 | Pełny wyższy 1200 Hz | ton 1200 Hz |
| 24 | Pełny wyższy 1400 Hz | ton 1400 Hz |
| 25 | Pełny wysoki 1650 Hz | ton 1650 Hz |
| 26 | Pełny krótki | długość 16 ms, podtrzymanie 4 ms, zanik 5 ms |
| 27 | Pełny zwarty | długość 22 ms, podtrzymanie 6 ms, zanik 7 ms |
| 28 | Pełny długi | długość 34 ms, podtrzymanie 13 ms, zanik 11 ms |
| 29 | Pełny podtrzymany | długość 38 ms, podtrzymanie 20 ms |
| 30 | Pełny łagodny | harmoniczne 1/.18/.08/.04, drive 1.3 |
| 31 | Pełny jasny | harmoniczne 1/.5/.32/.22, podtrzymanie 10 ms |
| 32 | Pełny zaokrąglony | harmoniczne 1/.2/.1/.06, drive .9 |
| 33 | Pełny nasycony | drive 2.8 |
| 34 | Pełny niski i gęsty | 850 Hz, długość 34 ms, podtrzymanie 13 ms, drive 2.5 |
| 35 | Pełny wyższy i zwarty | 1350 Hz, długość 20 ms, podtrzymanie 5 ms, zanik 6 ms, drive 2.2 |

To celowo bliscy krewni 06, nie 15 całkowicie innych instrumentów.
Numery 21–25 badają wysokość, 26–29 długość korpusu, 30–33 charakter harmoniczny,
a 34–35 łączą zmiany. Oceniaj względem 06 przy tych samych ustawieniach.
Przykład: „06 nadal najlepszy; 23 i 28 zostają; 31 za ostry”.

## Jak wybierać

Ustawienia → Dźwięk → **Eksperymentalne — 20 barw**. Wybór zamyka listę,
nie uruchamia dźwięku; przy działającym metronomie zmiana nastąpi na granicy
taktu. W ustawieniach zawsze widać nazwę wybranej barwy. Osiem poprzednich
pozostaje w przyciskach powyżej. Lista ma przewijanie i nie wymaga rozciągania
ekranu do 43 przycisków. Głośność zacznij od niższej wartości.

| Nr | Nazwa | Charakter syntezy | Długość |
| --- | --- | --- | ---: |
| 01 | Elektroniczny niski | 630 Hz, podtrzymany korpus | 24 ms |
| 02 | Elektroniczny średni | 1450 Hz, podtrzymany korpus | 24 ms |
| 03 | Elektroniczny wysoki | 2750 Hz, jasny sygnał | 18 ms |
| 04 | Elektroniczny dwutonowy | 1150 + 1930 Hz | 28 ms |
| 05 | Impuls krótki | 1870 Hz i nieparzyste harmoniczne, szybki zanik | 8 ms |
| 06 | Impuls pełny | 1050 Hz i harmoniczne, korpus i łagodne nasycenie | 28 ms |
| 07 | Impuls jasny | 2250 + 4500 Hz, mocniejszy korpus | 20 ms |
| 08 | Impuls chropowaty | 1320 + 3960 Hz, szum i nasycenie | 32 ms |
| 09 | Drewno suche | 1350 + 3465 Hz, krótki rezonans | 11 ms |
| 10 | Klawesy | 2100 + 3280 Hz, skupiony atak | 16 ms |
| 11 | Drewno rezonujące | 730 + 1870 + 3100 Hz, wolniejszy zanik | 34 ms |
| 12 | Drewno twarde | 3100 + 3700 Hz i szum | 9 ms |
| 13 | Metal dzwonek | 880 + 1397 + 2213 Hz, dłuższy rezonans | 38 ms |
| 14 | Metal kowadełko | 1250 + 2017 + 3370 Hz i szum | 30 ms |
| 15 | Metal ping | 3450 Hz z cichą składową 1100 Hz | 22 ms |
| 16 | Metal suchy | 2450 + 3990 Hz i szum, szybki zanik | 12 ms |
| 17 | Trzask | filtrowany szum z krótkim tonem 1900 Hz | 8 ms |
| 18 | Szczotka | niżej filtrowany szum, podtrzymany korpus | 30 ms |
| 19 | Obręcz | 1690 + 2810 Hz i szum | 15 ms |
| 20 | Atak szerokopasmowy | szerzej filtrowany szum z nasyceniem | 37 ms |

Podane częstotliwości dotyczą zwykłego uderzenia. Akcent podnosi je o 22%
i zmienia ziarno szumu. Składowe tonalne powyżej 45% sample rate są pomijane;
na niskich częstotliwościach próbkowania charakter barwy może się zmienić.
Nasycenie `tanh` dotyczy syntezy, nie jest twardym obcinaniem próbek PCM.
Wszystkie kliki są następnie odcentrowane i normalizowane do szczytu 29162.
Nie podbijamy głośności systemowej ani nie dodajemy LoudnessEnhancer.

## Jak przekazać wyniki

Najpierw porównaj w takich samych warunkach: to samo urządzenie, BPM, podział,
głośność aplikacji i systemu. Potem sprawdź słyszalność podczas grania.
Możesz napisać: „Lenovo: 02 i 06 zostają; 15 za ostry; 18 ginie przy graniu”.
Ważne są: słyszalność pulsu, nieprzyjemna ostrość, czytelność akcentu i zmęczenie.
Nie trzeba oceniać wszystkich naraz. Nie usuwamy kandydatów przed Twoją oceną.

## Referencja Metronome Beats

2026-10-20 odczytano lokalnie `assets/sounds.sf2` z aplikacji zainstalowanej
na Lenovo. Nie uruchamiano aplikacji ani odsłuchu; jej APK nie jest zasobem
naszego projektu ani plikiem przeznaczonym do publikacji. Bank zawiera presety
Claves, Tick, Cowbell, templeblock_hi1 i Standard. Sample m.in.:

| Sample | Sample rate | Zapisana długość | Szczyt PCM | Szczyt widma surowej próbki |
| --- | ---: | ---: | ---: | ---: |
| wittner | 44100 Hz | 95,941 ms | 25216 | około 1334 Hz |
| Clave ff(L) | 44100 Hz | 362,540 ms | 24604 | około 1647 Hz |
| templeblock_hi | 44100 Hz | 362,653 ms | 23239 | około 935 Hz |

To analiza surowych próbek, nie pomiar toru odtwarzania. Nie ustalono,
którego presetu i wysokości używa użytkownik ani jak silnik SoundFont
kształtuje głośność, obwiednię lub resampling. Długość pliku nie jest długością
słyszalnego ataku. Nie dowodzi to przewagi ich silnika ani konkretnej przyczyny
różnicy głośności. Nie kopiujemy próbek ani banku do aplikacji; nowe barwy
powstają z niezależnych formuł i lokalnego deterministycznego szumu.

## Analiza bez odtwarzania

Test `ClickBankTest` eksportuje `app/build/reports/audio/v011/`:
`bank-44100.wav`, `bank-48000.wav` (43 barwy, zwykła/akcentowana; slot 300 ms),
`legacy-then-new-*.wav` (zamrożona 0.8 kontra obecne trzy stare barwy), `levels.csv`.
Kolejność banku: osiem dotychczasowych, następnie 01–35, każda zwykła i akcentowana.
`impulse-family-*.wav` to osobne porównanie 06, następnie 21–35, także zwykłe
i akcentowane, slot co 300 ms. Kolumna `energyVs06Db` w CSV porównuje energię
każdego kliku do 06 przy tym samym sample rate i rodzaju akcentu.
Raport podaje RMS wewnątrz kliku, energię odniesioną do jednej sekundy oraz crest
factor. Nie porównujemy RMS klików różnej długości jako jedynej miary głośności.

```bash
python scripts/analyze-click-bank.py
```

Skrypt potrzebuje tylko standardowego Pythona 3. Sprawdza nagłówki WAV,
szczyty i końce oraz zapisuje `spectrum.csv`: szczyt widma, centroid i udział
energii poniżej 1 kHz, 1–4 kHz oraz powyżej 4 kHz. FFT używa okna Hanna;
nie zmienia odtwarzanego PCM. Żadne z tych narzędzi nie odtwarza dźwięku.
Raporty nie mierzą SPL, słyszalności przy instrumencie ani fizycznej precyzji audio.
