# Dzisiejszy czas praktyki

Karta **Dzisiaj** znajduje się na górze Statystyk. Czyta sumę `PracticeDay`
dla bieżącej daty z bazy — wszystkich sesji, również krótkich, trwających
oraz planowanej ciszy. Nie dodaje live timera do zapisanych danych.
Istniejący recorder checkpointuje około co 5 sekund i na końcu sesji;
wartość może więc pozostawać przez kilka sekund za bieżącą praktyką.
Nie jest to pomiar fizycznej latencji ani jittera.

Wyświetlanie rozróżnia ładowanie, rzeczywiste 0 oraz błąd odczytu z możliwością
ponowienia. Przy zmianie daty poprzednia wartość nie jest prezentowana jako
wynik nowego dnia. Format zachowuje godziny, minuty i sekundy, bez zawijania
po 24 godzinach ani zaokrąglania w górę.

Data jest obserwowana wyłącznie, gdy ekran statystyk jest aktywny. Następna
północ jest obliczana kalendarzowo, z uwzględnieniem DST. Zmiany czasu/strefy
systemu i powrót do ekranu ponownie ustalają datę i termin odświeżenia.
Dane historyczne zachowują daty wynikające ze strefy zapisanej przy sesji;
zmiana strefy urządzenia nie przepisuje starej historii.

Dodano wyłącznie zapytanie DAO i odczyt UI. Room nadal ma wersję 2, bez
zmiany tabel ani migracji. Format `.ombak` v1, presety, zapis sesji, silnik
PCM i brzmienia pozostają bez zmian. Nie wprowadzono progów aktywności;
rozbudowane Statystyki 2.0 są osobnym etapem.

Docelowo jest to mała poprawka użytkowa proponowana do 1.0.1. Ten PR nie
zmienia metadanych wydania, nie tworzy tagu, podpisu ani publikacji APK.
Kod jest niezależny od diagnostyki DEV w PR #15/#16 i plakietki w PR #17.

Walidacja obejmuje JVM stanów odczytu, checkpointów, formatowania i północy
DST; instrumentację na izolowanej bazie (wiele sesji, checkpointy, północ)
oraz odczyt karty bez uruchamiania silnika. Pełne wyniki i SHA znajdują się
w opisie PR po zakończeniu CI. Audio/wibracje pozostają bez zgody;
nie wykonujemy operacji na fizycznych urządzeniach.
