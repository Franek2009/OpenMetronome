# Ćwiczenie w 1.1 — implementacja rozwojowa

Publiczne 1.0.0 pozostaje niezmienione. Poniższy zakres dotyczy linii 1.1 beta;
dostępność w APK i wyniki potwierdza opis konkretnego wydania.

## Tempo

Baza nadal wynosi 20–300 BPM. Przyciski 100/75/50/25% zmieniają wyłącznie
mnożnik, a nie bazę. Przykład: 121 × 75% = 90.75 BPM. Harmonogram przechowuje
pozycje jako dokładne ułamki ramek; nie zaokrągla BPM do liczby całkowitej.
Tap i przyciski zmieniają bazę; ręczne ustawienie bazy wyłącza rampę jak dotąd.
Rampa zmienia bazę, procent pozostaje stały; sam procent nie resetuje treningu.

Domyślnie zmiana czeka na najbliższy niewyrenderowany takt. W Ustawieniach
można wybrać następny możliwy puls. Jego termin pozostaje ten sam; nowe tempo
określa odstęp po nim. Nie przepisujemy AudioTrack ani już wyrenderowanych
próbek. Przy 5 BPM między pulsami jest 12 s, więc reakcja nie jest natychmiastowa.
Inne zmiany programu/metrum nadal wchodzą od taktu. Głośność zachowuje osobne
łagodne przejście w bloku PCM.

## Ekran i rytm

Pełny ekran pokazuje wynikowe BPM, bazę, puls i takt. Wróć oraz przełączenie
orientacji nie restartują silnika ani sesji. Blokada dotyku pozostawia Stop
i Odblokuj; Wstecz najpierw odblokowuje ekran. Domyślnie pełny ekran może się
wygasić. Utrzymywanie ekranu i ograniczenie migania są niezależnymi
preferencjami widoku, a nie elementem presetu. Normalny widok zachowuje
utrzymywanie ekranu podczas odtwarzania.

Odliczanie 0/1/2 takty wybiera się w treningu; domyślnie jest wyłączone.
Korzysta z tego samego AudioTrack, z początkowego tempa i metrum, bez ciszy
treningowej ani podziałów. Rampa i trening zaczynają się na pierwszym takcie
praktyki. Stop podczas odliczania nie tworzy sesji praktyki. Edycje podczas
odliczania czekają na praktykę; zmiana długości odliczania dotyczy następnego Start.

Metrum 5/4, 7/4, 5/8 i 7/8 jest w podmenu. W /4 BPM liczy ćwierćnuty,
w /8 ósemki — znaczenie 6/8 pozostaje bez zmian. Grupowanie 5/8 to 2+3/3+2,
a 7/8 to 2+2+3/2+3+2/3+2+2. Można dalej ręcznie zmieniać akcenty i wyciszenia.

[Makieta układu](images/practice-1.1-layout.svg). Dostosowanie do pionu,
poziomu, tabletu i dużej czcionki jest częścią regresji UI.

## Zgodność i odbiór

Stara konfiguracja: 100%, zmiana od taktu, brak odliczania. Identyfikatory
brzmień i starych metrów są niezmienne. Nowe ustawienia trafiają do presetów;
kopie danych i ograniczenia wstecz opisują [Statystyki 2.0](STATISTICS-2.md).

Testy sprzętowe audio usunięto na prośbę użytkownika. Pozostają testy PCM,
atrapy i zabezpieczenia przed audio/wibracjami. Odsłuch i praktyczna ocena
nowego wydania należą do użytkownika. Nie deklarujemy pomiaru fizycznej
latencji ani jittera.
