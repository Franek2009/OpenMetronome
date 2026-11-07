# Prototypy do samodzielnego odsłuchu — nie zasoby aplikacji

Każdy `*-demo.wav` zawiera osiem uderzeń przy 120 BPM; pierwszy i piąty to
akcent. Odsłuch uruchamia użytkownik. Skrypt nigdy nie odtwarza audio.

- `01-natural-demo.wav`: para naturalna, maksymalnie 40 ms.
- `02-short-demo.wav`: krótszy, 24 ms atak.
- `03-low-demo.wav`: obniżona wysokość ×0,8, maksymalnie 40 ms.

Obok znajdują się samodzielne `*-normal.wav` i `*-accent.wav` (48 kHz,
16-bit mono). Akcent to pochodna wysokości ×1,22 względem zwykłego kliku,
nie osobne nagranie. Normalizacja ogranicza szczyt do 29162/32768;
fade 0,25/1,5 ms, korekcja DC, interpolacja windowed-sinc.

Źródło wszystkich prototypów: **Druminfected, Metronome.wav**, 2014-10-04,
https://freesound.org/people/Druminfected/sounds/250552/
Licencja: **CC0 1.0**, https://creativecommons.org/publicdomain/zero/1.0/
Strona źródła została sprawdzona podczas bieżącego przeglądu.
SHA-256 oryginału:
`4a0309f22f3bf478911d73f724c9ad9520dfec8fbe2f8390252ec92b50cdc140`.
Oryginał pozostaje nietknięty.

Odtworzenie plików: `python3 assets/samples/previews/prepare-review.py`
(wymaga NumPy). Sumy, długość i wartości PCM: `measurements.json`.
To prototyp DSP; nie jest deklarowany jako bitowo identyczny z Kotlin
`SamplePcm`. Przed integracją wymagane: rzeczywista ścieżka silnika,
pełna kwalifikacja jakości, zgoda użytkownika i testy regresyjne.
Pliki demo celowo przekraczają limit 40 ms, ponieważ są sekwencją odsłuchową.

Pierwsze trzy pary pochodzą wyłącznie z Druminfected. Dalsze pochodne VCSL
mają osobną ewidencję poniżej. Dzwonek Seidhepriest nie jest używany.

## VCSL — dodatkowe propozycje, jeszcze bez akceptacji odsłuchowej

Indywidualne metadane i licencja CC0 zostały potwierdzone; szczegóły
w `../PROVENANCE.md`. Ten sam skrypt odtwarza także cztery pary:
`04-cowbell-one`, `05-cowbell-two`, `06-tom-high`, `07-tom-low`.
Każda ma `*-demo.wav`, `*-normal.wav`, `*-accent.wav`.
Pomiary i sumy: `vcsl-measurements.json`. W aplikacji ich jeszcze nie ma.
