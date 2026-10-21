# Brzmienia i własne sample — 0.12

Ustawienia → Dźwięk → **Brzmienie**. Lista zawiera sześć wybranych klików:

| Nazwa | Dawny numer | Charakter |
| --- | --- | --- |
| Pełny | 06 | Wybrany wzorzec, 1050 Hz, zwarty korpus |
| Niski | 21 | Wersja 750 Hz |
| Jasny | 23 | Wersja 1200 Hz |
| Wysoki | 25 | Wersja 1650 Hz |
| Krótki | 26 | Korpus skrócony do 16 ms |
| Długi | 29 | Korpus podtrzymany, 38 ms |

Przebiegi nie zostały zmienione — uporządkowaliśmy listę i nazwy.
Wcześniejsze podobne warianty zniknęły z wyboru. Istniejące presety i ustawienia
nadal odtwarzają swoje oryginalne brzmienia, opisane jako „Archiwalne: …”.
Nowa instalacja zaczyna od Pełnego. Sam wybór nie rozpoczyna odtwarzania.

## Import

1. Zatrzymaj metronom i wybierz **Importuj sample**.
2. W systemowym oknie wskaż WAV, MP3 lub OGG (Vorbis/Opus).
3. Poczekaj na przygotowanie; nowy sample zostanie wybrany, ale nie odtworzony.
4. Zacznij odsłuch od niższej głośności i sam naciśnij Start.

Limit pliku: 10 MiB, długość do 10 sekund, mono lub stereo, 8–192 kHz.
WAV obsługuje zwykły RIFF/WAVE z liniowym PCM 8/16/24/32-bit albo float32;
nie obsługuje ADPCM, RF64 ani WAVE_FORMAT_EXTENSIBLE. Inne pliki o rozszerzeniu
WAV mogą zostać odrzucone z komunikatem. Format sprawdzamy według zawartości,
nie samego rozszerzenia ani MIME dokumentu. Dekoder platformowy może odmówić
obsługi uszkodzonego lub nietypowego pliku; aplikacja pokaże błąd bez zmiany wyboru.

**To import krótkiego kliku, nie odtwarzanie całego nagrania.** Usuwamy DC,
szukamy pierwszego sygnału co najmniej 1% szczytu z krótkim zapasem przed atakiem,
konwertujemy stereo do mono i zachowujemy maksymalnie 40 ms. Wygładzamy końce
i normalizujemy do szczytu 29162. Nie podnosimy głośności systemowej.
Plik całkowicie niemy, z samym DC lub nieprawidłowymi wartościami PCM zostanie
odrzucony. Sygnały stereo w przeciwfazie mogą zniknąć przy konwersji do mono;
takie pliki należy przygotować wcześniej jako mono.

Z jednego pliku przygotowujemy też akcent o tonie wyższym o 22%; jego długość
jest odpowiednio krótsza. Resampling stosuje filtr dolnoprzepustowy.
Import nie uruchamia podglądu, audio focus, AudioTrack ani wibracji.

## Przechowywanie i presety

- W bibliotece można mieć 32 sample. Nazwy mogą się powtarzać: każdy plik ma
  niezależny identyfikator i nie nadpisuje poprzedniego. Nazwę można zmienić.
- W aplikacji przechowujemy własną kopię przygotowanego mono PCM, nie odnośnik
  do dokumentu. Przeniesienie lub usunięcie oryginału nie psuje brzmienia.
- Zmieniona głośność nie zmienia pliku; ustawienia i presety zapisują identyfikator
  sampla. Biblioteka jest lokalna i nie synchronizuje się między urządzeniami.
- Import, zmiana nazwy i usuwanie wymagają zatrzymania metronomu. Podczas
  odtwarzania można wybierać między już dostępnymi samplami na granicy taktu.
- Usunięcie wymaga potwierdzenia. Sample używany przez preset jest chroniony:
  najpierw zmień brzmienie tego presetu. Nie przepisujemy presetów automatycznie.
- Usunięcie aktualnie wybranego sampla przełącza wybór na Pełny. Usuwana jest
  wyłącznie kopia w aplikacji; źródło i historia ćwiczeń pozostają nietknięte.
- Brak lub uszkodzenie prywatnego pliku powoduje komunikat i brak odtwarzania
  wybranego sampla, bez automatycznej zamiany na inny klik. Pozostałe brzmienia
  pozostają dostępne.

Nie dodaliśmy szerokich uprawnień do pamięci, dostępu do sieci, nagrywania
mikrofonem, edytora waveform ani eksportu/importu biblioteki między telefonami.
Odinstalowanie aplikacji lub wyczyszczenie jej danych usuwa prywatne kopie;
zachowaj oryginalne pliki. Aktualizacja zgodnym podpisem nie wymaga odinstalowania.

Wyniki testów i ograniczenia: [weryfikacja 0.12](V0.12-VALIDATION.md).
