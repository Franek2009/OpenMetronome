# Pochodzenie i kwalifikacja nowych sampli

Oryginały użytkownika pozostają nienaruszone. Folder `source/` nie jest
zasobem APK. Źródła i pliki demonstracyjne nie są odtwarzane przez testy.

## Zintegrowane warianty po akceptacji odsłuchu użytkownika

Autor: **Druminfected**, `Metronome.wav`, 2014-10-04.
https://freesound.org/people/Druminfected/sounds/250552/
CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
Potwierdzono na stronie zasobu; SHA oryginału i pomiary w
`source-measurements.json`. Parametry 44,1 kHz/16-bit/stereo i czas 0,7576 s
zgodne z metadanymi strony. Nie deklarujemy porównania bajtów z chronionym
oryginalnym downloadem Freesound.

Trzy pochodne: `SAMPLE_NATURAL` (Naturalny), `SAMPLE_SHORT` (Suchy),
`SAMPLE_LOW` (Naturalny niski). Normal i akcent zapisane jako osobne PCM, akcent
opracowany przez zmianę wysokości ×1,22, nie osobne nagranie autora.
Odsłuch użytkownika zaakceptowany jako „co do dźwięku, to spoko”; nie jest
pomiarem fizycznej latencji ani jittera. Klip naturalny i niski: 40 ms,
suchy: 24 ms. Niski: wysokość bazowa ×0,8.

Reprodukcja: `previews/prepare-review.py` (NumPy), następnie
`scripts/generate-sampled-bank.py`; ten drugi ma tryb `--check` i nie wymaga
NumPy. Zintegrowane próbki 48 kHz bitowo odpowiadają zaakceptowanym WAV-om,
bez ponownego przetwarzania/transponowania akcentu. Inne częstotliwości
przechodzą przez dotychczasowe `SamplePcm.click(..., accented=false)`.
Nie podmieniamy żadnego z 43 wcześniejszych identyfikatorów ani PCM.

Bank źródłowy: 9984 próbki ×2 B = 19968 B plus obiekty; osobny bank renderera
zależy od częstotliwości i zawiera kopie. Nie jest to pomiar całej sterty.

## VCSL / CLR — legalne, oczekują na wybór odsłuchowy

Cztery WAV-y w `source/crl.fm/` pochodzą z **Versilian Studios / VCSL contributors**.
https://versilian-studios.com/vcsl/
https://github.com/sgossner/VCSL
CC0 potwierdzono u wydawcy i w indywidualnych metadanych CLR.
Metadane z https://clr.fm/music/data.json zachowano w
`licenses/clr-vcsl-metadata.json`, a tekst licencji w `licenses/VCSL-CC0.txt`.
Dostarczone pliki porównano z adresami `file` tych metadanych: wszystkie
cztery identyczne bajtowo. Nazwa folderu użytkownika `crl.fm` pozostaje.

Podglądy `04-cowbell-one`, `05-cowbell-two`, `06-tom-high`, `07-tom-low`
znajdują się w `previews/`; przetwarzanie jak wyżej, 40 ms. To nie są jeszcze
barwy w aplikacji. Cowbelle tracą energię przy uśrednianiu stereo; tomy
istotną część ogona po skróceniu. Normalizacja nie odtwarza wybrzmienia.
Wybór wymaga odsłuchu użytkownika, nie samej liczby dostępnych plików.

## Odrzucony z dystrybucji: Seidhepriest

`191959__seidhepriest__metronome-bell-96.wav`:
https://freesound.org/people/Seidhepriest/sounds/191959/
**CC BY-NC 4.0**, https://creativecommons.org/licenses/by-nc/4.0/
Strona opisuje nagranie brzmienia standardowego Roland GS drumkit.
Nie kwalifikujemy jako CC0. Nie dołączamy do APK, PR ani pochodnych
podglądów; oryginał użytkownika pozostaje lokalnie bez zmian.

## Pomiary

`scripts/audit-source-samples.py` wykonuje tylko odczyt WAV; mierzy każdy
kanał, mono, DC, RMS, pełną skalę, początek i energię okna 40 ms.
Zero próbek pełnej skali nie dowodzi braku wcześniejszego ograniczenia
sygnału. Pomiary nie zastępują odsłuchu ani laboratoryjnej analizy wyjścia.
