# Dzisiejszy czas praktyki

Karta z PR #18 wchodzi do 1.1 beta razem ze [Statystykami 2.0](STATISTICS-2.md).
Pierwotnie rozważana osobna poprawka 1.0.1 nie jest osobnym wydaniem.

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

Pierwotny PR #18 dodał zapytanie i odczyt UI bez migracji. Kolejny PR #20
rozbudował statystyki: Room ma wersję 3, backup v2 i progi 20 s/5 min.
Karta Dzisiaj nadal pokazuje cały czas, a kwalifikowany czas i aktywny dzień
są oddzielnie opisane w Podsumowaniu. Nie uzależnia się od diagnostyki DEV.

Walidacja obejmuje JVM stanów odczytu, checkpointów, formatowania i północy
DST; instrumentację na izolowanej bazie (wiele sesji, checkpointy, północ)
oraz odczyt karty bez uruchamiania silnika. Pełne wyniki i SHA znajdują się
w opisie PR po zakończeniu CI. Audio/wibracje pozostają bez zgody;
nie wykonujemy operacji na fizycznych urządzeniach.
