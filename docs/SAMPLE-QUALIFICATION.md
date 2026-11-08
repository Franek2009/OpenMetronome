# „Drewniany” — odłożona kwalifikacja na 1.0.1

> Aktualizacja domknięcia 1.1: oryginały dostarczono. Trzy pochodne
> Druminfected zaakceptowano odsłuchowo i zintegrowano na gałęzi roboczej.
> VCSL CC0 oczekuje na wybór; Seidhepriest BY-NC odrzucony z dystrybucji.
> Aktualne pomiary i pochodzenie: [PROVENANCE](../assets/samples/PROVENANCE.md).
> Poniższy audyt historyczny zachowano, nie jest bieżącym statusem prac.


Decyzja wydawnicza: barwa odłożona do **1.0.1**, poza RC2 i stabilnym 1.0.0.
Brak WAV-ów nie blokuje tych wydań. Zachowujemy audyt jako materiał przyszłych prac.

Stan analizy: **oczekiwanie na oryginalne WAV-y** w
`assets/samples/source/`. Nie wdrożono nowej barwy. Nie odtwarzano dźwięku.

## Pochodzenie

Autor/uploader: **Druminfected**, publikacja 2014-10-04. Na obu stronach
ponownie potwierdzono CC0 oraz opis dźwięku zbliżonego do woodblock:

- [Metronome.wav, 250552](https://freesound.org/people/Druminfected/sounds/250552/):
  proponowany zwykły klik; według metadanych strony WAV 44,1 kHz, 16-bit stereo,
  około 0,757 s.
- [MetronomeUp.wav, 250551](https://freesound.org/people/Druminfected/sounds/250551/):
  proponowany oddzielny akcent; według strony ten sam format, około 0,601 s.
- Licencja: [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).
  Zachować autora i źródło także bez formalnego obowiązku atrybucji.

Oryginalny download wymaga logowania; wcześniejsze próby zwróciły HTML
formularza, nie WAV. MP3/OGG z CDN to podglądy stratne, nie oryginały.
Nie konwertować podglądu do WAV i nie oznaczać go jako źródła bezstratnego.
Pixabay 85688 nie wchodzi do planowanego PR: brak pobranego pliku i porównania
pochodzenia/bajtów; nie zakładamy identyczności z Freesound.

## Wyniki wcześniejszego pomiaru podglądów — wyłącznie orientacyjne

Lokalne dowody: `.tooling/sound-audit-rc2/measurements.json`, `downloads.json`
i `pcm-checks.txt`. Nie są pomiarami oryginalnych WAV-ów ani fizycznego audio.

| Podgląd | Szczyt mono w czasie od początku | Energia w pierwszych 40 ms | Przygotowane PCM 48 kHz |
| --- | --- | --- | --- |
| 250552 MP3/OGG | około 5,19 ms | 98,94–98,96% | 1920 ramek, około −1,01 dBFS, brak pełnoskalowych próbek |
| 250551 MP3/OGG | około 2,45–2,79 ms | 99,44–99,48% | 1920 ramek, około −1,01 dBFS, brak pełnoskalowych próbek |

Po przygotowaniu: pierwsza/ostatnia próbka zero, DC poniżej 0,01 LSB.
To potwierdza techniczną możliwość krótkiej pary, nie jakość oryginalnego
ataku. Dekodowany stereo OGG 250551 miał 89 wartości >= pełnej skali,
szczyt +0,25 dBFS; MP3 nie miał takich wartości. Może to być efekt kodeka
lub źródła. Normalizacja nie naprawia wcześniej obciętej fali: trzeba
obejrzeć oryginał, także przed uśrednieniem kanałów. Komentarz użytkownika
na stronie akcentu również sygnalizuje clipping; nie traktujemy go jako
własnego pomiaru ani automatycznego powodu odrzucenia.

## Fakty o obecnym kodzie i minimalna zmiana

Widoczne sześć barw to `IMPULSE_FULL`, `FULL_LOW_750`, `FULL_HIGH_1200`,
`FULL_HIGH_1650`, `FULL_SHORT`, `FULL_HELD`. Renderer nadal buduje także
historyczne ID. Konfiguracje zapisują nazwy enum, nie ich pozycje.
Istniejące archiwalne `WOOD` ma już etykietę „Drewniany”: **nie zastępować
jego generatora** nowym nagraniem. Nowa barwa potrzebuje odrębnego ID;
archiwalna etykieta może dostać dopisek rozróżniający tylko przy wykazanej
niejednoznaczności UI, bez zmiany ID i dotychczasowego PCM.

`SamplePcm.prepare()` wybiera atak, skraca do 40 ms, resampluje do 48 kHz,
usuwa DC i stosuje fade 0,25/1,5 ms oraz normalizację do 29162.
`SamplePcm.click(..., accented=true)` transponuje własny sample o 1,22.
Dla nowej pary należy przygotować klik/akcent osobno i resamplować każdy
bez tej transpozycji. `PcmRenderer` już wybiera parę normal/accented i
konstruuje bank przed renderowaniem; nie ma uzasadnienia przebudowy zegara,
treningu lub silnika. Wybór konkretnego sposobu ładowania po analizie WAV-ów.

## Kwalifikacja oryginałów po dostarczeniu

1. Potwierdzić RIFF/WAVE, długość, kanały, rozdzielczość, SHA-256 i powiązanie
   plików z konkretnymi stronami. Zachować nietknięte źródła i zapis konwersji.
2. Zmierzyć osobno oba kanały oraz mono: szczyt, próbki pełnoskalowe i płaskie
   plateau, DC, RMS, onset i czas szczytu, skoki, energię i ogon po 40 ms.
   Sprawdzić fazę stereo i stratę energii po downmixie.
3. Porównać pełne źródło i wersję 40 ms po rzeczywistym `SamplePcm`: attack,
   granice/fade, wybrzmienie, RMS i energię. Nie dodawać długiego sampla przez
   podniesienie limitu bez dowodu konieczności; odrzucić nieudany materiał.
4. Porównać z sześcioma istniejącymi barwami i między normal/accent; raport
   DSP nie zastępuje oceny słuchowej użytkownika. Brak słyszalnej różnicy
   lub uszkodzony akcent to powód odrzucenia, nie zwiększania liczby barw.
5. Dopiero po pozytywnej kwalifikacji implementacja jednej pary, osobny PR,
   pełna regresja przyszłej wersji i akceptacja brzmienia przed scaleniem.

## Budżet i dowody wydajności

Dwie maksymalne tablice 40 ms/48 kHz/16-bit mono zajmują **7680 bajtów PCM**
(bez nagłówków tablic, dodatkowego przechowywania źródła i kosztu obiektów).
Dwa kanoniczne WAV-y: 7768 bajtów przed kompresją ZIP. To wyliczony budżet,
**nie zmierzony przyrost APK ani sterty**. Przy 44,1 kHz para to 7056 bajtów
PCM; dla innych częstotliwości policzyć faktyczne tablice i wszystkie kopie.
Zmierzony wcześniej ZIP podglądów nie jest wynikiem dla przyszłych WAV-ów.

Przed PR zmierzyć unsigned APK przed/po w tym samym środowisku, czas budowy
banku, zajęte tablice i brak alokacji/I/O nowej ścieżki w pętli. Porównać
ramki pulsów i podziałów przy zmianach BPM, głośności i ciszy. Scheduler
nie powinien się zmienić; cyfrowe indeksy ramek nie dowodzą fizycznego jittera.

## Przyszła barwa — plan na 1.0.1, poza bramkami RC2/1.0

- [ ] Oryginały WAV, SHA-256, pochodzenie i CC0; analiza obu kanałów przed mono.
- [ ] Kwalifikacja kliku oraz osobnego akcentu po przygotowaniu do maks. 40 ms;
  brak uszkodzenia ataku/wybrzmienia, clippingu i nowych trzasków.
- [ ] Jeden nowy trwały identyfikator, dopisany do enum; istniejące ID i PCM
  sześciu barw bitowo niezmienione. Nie zastępować historycznego `WOOD`.
- [ ] Oddzielny akcent wybierany z nagrania, bez automatycznej transpozycji.
  Ten sam scheduler/renderer; bez I/O w pętli renderowania i bez migracji Room.
- [ ] Testy round-trip konfiguracji, presetów, historii i `.ombak` v1,
  również z historycznymi ID. Nowe ID nie obiecuje obsługi przez stare APK.
- [ ] PCM offline na 44,1/48 kHz i obsługiwanych skrajnych częstotliwościach;
  głośność, granice, długość, DC, clipping, akcent, zmiany konfiguracji,
  wyciszenia, podziały i niezmienione indeksy ramek pulsów.
- [ ] Zmierzone przyrosty rozmiaru APK i pamięci PCM oraz koszt budowy banku;
  nie zastępować ich szacunkiem ani deklaracją fizycznej precyzji audio.
- [ ] JVM, parsery/skrypty, PCM offline, Lint debug/release, debug/test/release,
  pełne ciche API 26/35, aktualizacja i backup/restore PASS na końcowym HEAD PR.
  Audio/wibracje pominięte jawnie, zero nieoczekiwanych pominięć.
- [ ] Akceptacja brzmienia kliku i akcentu przez użytkownika, CI 5/5 PASS
  i osobna zgoda na scalenie. Nie uruchamiać samodzielnie fizycznego odsłuchu.


Bieżący proces wydania (bez tej barwy): [RC2/1.0](V1.0-RC2-READINESS.md).
