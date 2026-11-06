# Źródła logo OpenMetronome

`Logo.svg` jest projektem użytkownika OpenMetronome wykonanym w Inkscape.
`Logo.png` jest jego eksportem 496×496 px. Oryginały zachowano bez zmian,
łącznie z metadanymi edycyjnymi SVG. Zasoby Androida są osobnymi pochodnymi.
`assets/.gitattributes` zapobiega normalizacji końców linii oryginalnych SVG
podczas zapisu w Git.
Kompozycja obejmuje przeskalowanie i rozmieszczenie dwóch symboli, zielone tła
oraz dodatkowy element przy wahadle. Nie przypisujemy SVG Repo autorstwa
całej kompozycji.

| Oryginalny plik bazowy | Źródło | Uploader podany przez serwis | Licencja |
| --- | --- | --- | --- |
| `metronome-tempo-beat-bpm-svgrepo-com.svg` | [SVG Repo 390025](https://www.svgrepo.com/svg/390025/metronome-tempo-beat-bpm) | SVG Repo | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |
| `musical-note-music-svgrepo-com.svg` | [SVG Repo 204794](https://www.svgrepo.com/svg/204794/musical-note-music) | SVG Repo | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |

Sprawdzono 2026-11-01: obie strony podają CC0, a pliki pobrane z odnośników
`/show/390025/...svg` i `/show/204794/...svg` mają identyczne sumy z plikami
bazowymi w tym katalogu. Źródła dokumentujemy niezależnie od braku obowiązku
atrybucji w CC0. Autorzy bazowych ikon nie są wskazani osobno od uploaderów.

SHA-256 oryginałów:

```text
e082be4c1cb141dbc60fd2bc88329fda48a0e2476d7c9689944a1f6e29fd0622  Logo.svg
3c405c6486b5b3bcaba69da36a0ba36afa4ef85f583b1bf4e79f9d41b781b2db  Logo.png
47b2120ade2214f16892993eec847f4780901e088b0263734713bd214507336c  metronome-tempo-beat-bpm-svgrepo-com.svg
7af09314b23d7f48566e06c56a94bce8abf4906224dae6eba5cfd67a63267592  musical-note-music-svgrepo-com.svg
```

## Generowanie zasobów

```bash
python3 scripts/generate-launcher-icons.py
```

Wymagane Python 3 i Inkscape. Skrypt czyta SVG, nie zapisuje do niego zmian.
Zachowuje dokładne dane ścieżek i stosuje jeden wspólny współczynnik skali
oraz przesunięcie całej kompozycji. Tła SVG nie należą do foregroundu.
Kolorowe tło Androida jest jednolite `#00FF00`; zaokrąglenie wynika z maski
launchera. Foreground ma czarne symbole i przezroczyste otwory, a osobny
monochrome tę samą geometrię/alfa w bieli, do barwienia przez launcher.

Dopasowanie używa najmniejszego okręgu obejmującego próbkowane obrysy Béziera
(128 kroków na krzywą), a promień docelowy wynosi 32,8 dp. Daje to 0,2 dp
zapasu do bezpiecznej strefy o promieniu 33 dp. Ścieżki wynikowe nie są
aproksymowane; próbkowanie służy wyłącznie obliczeniu skali i centrowania.
Zmiana typu transformacji lub poleceń SVG na nieobsługiwane jest odrzucana.

Wyniki: dwa VectorDrawable, adaptive XML dla API 26/33, pięć klasycznych PNG
(mdpi 48, hdpi 72, xhdpi 96, xxhdpi 144, xxxhdpi 192 px) i
[podglądy 48/72 px](../docs/images/launcher-icon-preview.png).
Klasyczne PNG mają zaokrąglone zielone tło i przezroczyste narożniki,
bez ciemnozielonego obramowania. Nie rozszerzają wsparcia aplikacji poniżej
jej niezmienionego minSdk 26; zapewniają bitmapowe zasoby zastępcze.

Podglądy motywowane używają przykładowych jasnych/ciemnych palet. Rzeczywiste
kolory i kształt nadaje obsługujący tę funkcję launcher.

Specyfikacja: [Adaptive icons — Android Developers](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

## Oznaczenie DEV

`python3 scripts/generate-dev-launcher-icons.py` odczytuje geometrię gotowego
produkcyjnego foregroundu i dodaje własną geometryczną plakietkę DEV.
Napis nie używa fontu ani dodatkowego zasobu zewnętrznego. Wyniki trafiają
wyłącznie do `app/src/dev/res`: dwa VectorDrawable oraz pięć PNG.
Adaptive XML odziedziczone z main wybiera te zasoby przez nakładkę flavoru.
Monochrome ma wycięte litery i usuwa symbole spod plakietki, aby napis
pozostał czytelny po nadaniu koloru przez launcher. Cała plakietka mieści
się w strefie o promieniu 33 dp. Oryginały i zasoby produkcyjne są niezmienne.
[Porównanie stable/DEV, 48 i 72 px](../docs/images/dev-launcher-preview.png).
Po regeneracji głównej ikony uruchom również generator DEV, aby obie wersje
korzystały z tej samej aktualnej geometrii logo.

Generator DEV odczytuje wyłącznie lokalny VectorDrawable z repozytorium,
ogranicza wejście do 128 KiB UTF-8 i odrzuca DTD oraz deklaracje encji przed
parsowaniem. Nie pobiera zewnętrznego XML i nie wymaga nowej biblioteki.
Regresja wejścia: `python3 scripts/test-dev-icon-input.py`.
