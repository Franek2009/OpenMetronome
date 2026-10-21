# Działanie aplikacji

Dotknięcie BPM otwiera edycję liczbową; przyciski zmieniają tempo o ±1/±5.
Lampki przełączają zwykły puls → akcent → cisza. Akcent początkowy domyślnie
przechodzi najpierw w ciszę. Cisza lampki obejmuje wszystkie podziały pulsu;
akcent dotyczy jego pierwszego podziału, o ile ten nie jest wyciszony.
Przy zmianie metrum wspólne pozycje wzoru zostają, nowe są zwykłe.
Stare presety i ustawienia bez wzoru zachowują wcześniejsze zachowanie.
Suwak wyciszeń zaokrągla, zamiast obcinać ułamki; przyciski ±1% pozwalają
wybrać dokładnie każdą wartość, w tym zgłoszone 40%.

Statystyki → Eksportuj CSV zapisuje całą historię przez systemowy wybór pliku,
bez nowych uprawnień. UTF-8 z BOM, separator średnik, jeden wiersz na sesję:
ID, początek, checkpoint, strefa, czas w ms, status, początkowe BPM i metrum.
Trwająca sesja pochodzi z ostatniego checkpointu (zwykle co 5 s).
CSV nie jest kopią do importu ani pełnym zapisem zmian tempa podczas sesji.
Nieczytelna historyczna konfiguracja daje puste BPM/metrum; pozostała historia
nadal jest eksportowana. Błąd zapisu może pozostawić niepełny plik wyjściowy,
ale nie zmienia danych aplikacji. Bez PDF na tym etapie.

Weryfikacja i ograniczenia: [raport 0.8](V0.8-VALIDATION.md).

Metronom odtwarza generowany strumień PCM. Ekran pozwala ustawić tempo **20–300 BPM**,
metrum **2/4, 3/4, 4/4 lub 6/8** i akcent pierwszej miary. Domyślne ustawienia:
**120 BPM, 4/4, akcent włączony**. Interfejs jest po polsku i obsługuje
zmianę orientacji. Motyw jest zawsze ciemny.

Start rozpoczyna nowy takt, Stop natychmiast zatrzymuje odtwarzanie. Zmiany
tempa, metrum i akcentu podczas grania wchodzą razem na początku następnego
jeszcze niewyrenderowanego taktu; ostatni wybór wygrywa. W 2/4, 3/4 i 4/4
BPM oznacza ćwierćnuty, w **6/8 — ósemki, sześć kliknięć na takt**.
Akcent ma wyższy ton. „Tap tempo” wyznacza BPM z mediany do czterech ostatnich
odstępów stuknięć, bez uruchamiania odtwarzania. Pierwszy tap zaczyna pomiar,
odstęp poza zakresem 20–300 BPM rozpoczyna go na nowo, a powtórzenia poniżej
100 ms są ignorowane. Poprawny wynik wyłącza rampę tak jak ręczne BPM.
Opuszczenie ekranu metronomu resetuje pomiar, ale nie wybrane tempo.

Start/Stop jest w stałym pasku poza przewijanymi ustawieniami, również na
ekranach Presety, Statystyki i Ustawienia. Ustawienia → „Informacje” otwiera lokalną migawkę diagnostyki:
wersję, model telefonu, Android, bieżącą konfigurację, stan/błąd silnika i dostępne
liczniki audio. Raport nie zawiera numeru seryjnego, nazw presetów ani historii.
Nie jest zapisywany w logu ani wysyłany automatycznie; „Kopiuj raport” kopiuje
go do systemowego schowka na wyraźne żądanie użytkownika. Dane to migawka
renderera, nie pomiar rzeczywistego wyjścia głośnika.

Sekcja „Dźwięk” daje niezależną głośność aplikacji 0–100%. W 0.9 dostępne są
„Klasyczny”, „Drewniany”, „Krótki”, „Woodblock”, „Rimshot”, „Cowbell”,
„Klaśnięcie” i „Puls elektroniczny”. Publiczna 0.8 ma tylko pierwsze trzy.
W rozwojowej 0.12 jeden wybór „Brzmienie” zawiera Pełny, Niski, Jasny,
Wysoki, Krótki i Długi oraz własne sample. Dawne numery i eksperymenty
zniknęły z listy; wcześniejsze konfiguracje nadal zachowują swoje brzmienia
archiwalne. Nowe ustawienia używają Pełnego (dawne 06).
„Importuj sample” przygotowuje WAV/MP3/OGG jako klik do 40 ms, bez odsłuchu.
Import wymaga zatrzymania metronomu. [Import i biblioteka](CUSTOM-SAMPLES.md).
Przyciski telefonu nadal regulują
systemowe multimedia. Zmiana głośności dotyczy kolejnych renderowanych próbek,
z przejściem około 5 ms; wcześniej zbuforowane audio nie jest cofane.
Barwa zmienia się od następnego jeszcze niewyrenderowanego taktu.
0% nie zatrzymuje zegara, treningu ani zapisu sesji. Od 0.9 kliki są głośniejsze
domyślnie, także w istniejących presetach: nie ma osobnego trybu wzmocnienia.
Zacznij pierwszy odsłuch od niższej głośności. Wybór barwy przy zatrzymanym
metronomie nie uruchamia dźwięku. Barwy wbudowane są syntetyczne; własne pliki
są kopiowane do prywatnej biblioteki aplikacji.
Głośność i barwa są zapisywane również w presetach; stare konfiguracje
otrzymują swoje wcześniejsze wartości domyślne. W 0.12 migracja Room 1→2
dodaje metadane sampli bez kasowania dotychczasowych tabel.

Właścicielem sesji jest usługa `mediaPlayback`: Home, obrót i blokada nie
powinny przerywać ćwiczenia. Zatrzymanie z aplikacji lub powiadomienia kończy
sesję. Utrata audio focus i odłączenie słuchawek zatrzymują ją bez wznowienia.
Nie ma automatycznego odtwarzania po uruchomieniu aplikacji ani telefonu.

Sekcja „Trening pulsu” udostępnia losowe wyciszenia 0–100%, cykle taktów
słyszalnych/ciszy, progresję wyciszeń, rampy tempa i podziały 1×/2×/3×/4×
oraz punktowany 3:1. Akcenty/pominięcia podziałów edytuje się przyciskami.
Losowe wyciszenie dotyczy całego pulsu razem z jego podziałami. Oba rodzaje
ciszy można łączyć. Zmiana programu resetuje jego postęp na granicy taktu;
ręczna zmiana BPM wyłącza rampę tempa. Nowy Start resetuje cały trening.

Wizualizacja głównego pulsu działa również podczas treningowej ciszy.
Wibracje są domyślnie wyłączone. Podziały nie zwiększają częstotliwości wibracji.
Sygnalizacja podąża za licznikiem odtworzonych ramek, lecz dokładność
rzeczywistej synchronizacji audio/wibracji wymaga pomiaru urządzeniowego.

DataStore przechowuje ostatnie ustawienia. Ekran „Presety” zapisuje, wczytuje,
zmienia nazwy i usuwa konfiguracje (po potwierdzeniu). „Statystyki” pokazują
czas, liczbę sesji, historię i zestawienia dzienne/tygodniowe. Dane są lokalne
w Room; planowana cisza liczy się jako ćwiczenie. Checkpoint co 5 s ogranicza
utratę czasu przy przerwaniu procesu; błędy dysku mogą uniemożliwić zapis.

**To nie jest wydanie 1.0.** Test 30-minutowy został na życzenie użytkownika
odłożony do własnego użytkowania — nie jest zaliczony, ale nie blokuje wersji dev.
Odbiór sprzętowy nowych barw pozostaje osobny. Aktualne wyniki:
[raport 0.8](V0.8-VALIDATION.md); wcześniejsze wyniki:
[historia M2–M5](M2-M5-VALIDATION.md).
