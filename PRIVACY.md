# Prywatność OpenMetronome

Aktualizacja: 2026-10-30. Dotyczy aplikacji Android OpenMetronome.

OpenMetronome działa offline. Nie wymaga konta, nie zawiera reklam ani
analityki i nie przesyła danych do twórców. Aplikacja nie żąda uprawnienia
INTERNET. Ustawienia, presety i historia ćwiczeń są zapisywane lokalnie.

Systemowe powiadomienie umożliwia zatrzymanie odtwarzania w tle; zgoda na
powiadomienia zależy od Androida. Opcjonalne wibracje są domyślnie wyłączone.
Aplikacja nie nagrywa mikrofonu i nie korzysta z lokalizacji ani kontaktów.

Import własnych sampli WAV/MP3/OGG odbywa się po wybraniu pliku. Aplikacja
przygotowuje i zapisuje prywatną kopię krótkiego dźwięku; nie zmienia pliku
źródłowego ani nie przesyła go do twórców.

Eksport CSV, zapis i przywracanie kopii `.ombak` oraz kopiowanie raportu
diagnostycznego następują wyłącznie po Twoim działaniu. Kopia zawiera ustawienia,
presety, historię i własne sample; nie jest szyfrowana. Przywracanie wymaga
potwierdzenia zastąpienia bieżących danych. Przy błędzie cofania mogą pozostać
prywatne pliki odzyskiwania w pamięci aplikacji. Od beta.3 dziennik przywracania
przechowuje tymczasowo poprzedni i przywracany zestaw danych oraz sample.
Pozostaje w prywatnej pamięci aplikacji, jest używany do odzyskania po przerwaniu
i sprzątany po zakończeniu. Nie jest wysyłany do twórców ani do GitHuba. Plik trafia do miejsca wskazanego w systemowym oknie wyboru,
które może być także usługą chmurową wybraną przez Ciebie. Kopiowany raport
trafia do systemowego schowka. Dalej obowiązują zasady wybranego systemu/usługi.

Automatyczna kopia zapasowa aplikacji w Androidzie jest wyłączona. Usunięcie
danych aplikacji lub jej odinstalowanie usuwa lokalne dane; wyeksportowane
pliki pozostają w wybranym miejscu. CSV nie jest kopią do odtworzenia bazy; do tego służy ręcznie zapisana `.ombak`.

Pobieranie z GitHuba oraz zgłoszenia błędów odbywają się poza aplikacją
i podlegają zasadom GitHuba. Nie dołączaj prywatnej historii ani sekretów do
publicznych zgłoszeń. Kontakt z projektem:
[GitHub Issues](https://github.com/Franek2009/OpenMetronome/issues).
