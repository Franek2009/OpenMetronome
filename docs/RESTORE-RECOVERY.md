# Odzyskiwanie przywracania — od 0.13.0-beta.3

## Zasada

Przed podmianą aplikacja zapisuje prywatny dziennik: kompletną kopię poprzednich
danych, kompletną kopię przywracaną i znacznik gotowości. Archiwa zachowują
format `.ombak` v1. Ich pliki oraz wpisy katalogu są synchronizowane przed
pierwszą zmianą bazy, ustawień lub sampli.

Po przerwaniu procesu nowy proces wybiera jeden cały zestaw:

- Bez znacznika zatwierdzenia odtwarza poprzednie dane.
- Ze znacznikiem zatwierdzenia dokańcza przywrócony zestaw.
- Bez znacznika gotowości usuwa wyłącznie niedokończone przygotowanie dziennika;
  protokół nie dopuszcza wówczas jeszcze zmian w magazynach.

Znacznik zatwierdzenia powstaje po zapisie bazy i preferencji, synchronizacji
WAL/bazy, plików i katalogów. Zakończony dziennik jest atomowo przenoszony
poza aktywną nazwę i dopiero potem sprzątany. Przerwanie sprzątania nie usuwa
części aktywnej kopii potrzebnej do odzyskania. Podczas odtwarzania dziennik
pozostaje niezmieniony, więc także odzyskiwanie można przerwać i powtórzyć.

## Start aplikacji

`AppData.ready` zostaje zwolnione dopiero po odzyskaniu, załadowaniu biblioteki
i standardowym oznaczeniu niedokończonych sesji. Przed tym nie są przyjmowane
zapisy ani Start. Usługa czeka na tę samą barierę przed odczytem konfiguracji;
UI pokazuje przygotowanie danych zamiast ekranów opartych na niegotowym stanie.

Uszkodzony lub niedostępny dziennik blokuje edycję i Start, zachowując materiały.
Nie ma destrukcyjnego fallbacku bazy ani automatycznego kasowania nieczytelnego
archiwum. Pliki spoza własnego katalogu dziennika, w tym starsze materiały
odzyskiwania beta.2, nie są automatycznie usuwane.

## Co sprawdzamy

- JVM: wybór undo/redo, niekompletne przygotowanie, nieczytelny znacznik/archiwum
  i przerwanie atomowego wycofania nazwy aktywnego dziennika.
- Android: trwałe Room, DataStore i pliki; blokada podczas inicjalizacji oraz
  odzyskanie po błędzie samego cofania.
- Debug-only usługa `:restore_probe` używa wyłącznie katalogu cache z UUID.
  Osobny proces jest rzeczywiście zabijany po przygotowaniu, obu podmianach
  katalogu, zmianie bazy, zapisie ustawień, commicie bazy, synchronizacji,
  zatwierdzeniu i wycofaniu dziennika. Osobno zabijamy także odzyskiwanie.
  Po ponownym otwarciu porównujemy konfigurację, rekordy i dokładne bajty sampli.
  Usługa nie jest eksportowana i nie występuje w release APK.

Nie uruchamiamy silnika audio, wibracji ani kasowania danych aplikacji użytkownika.

## Granice gwarancji

Testy zabicia procesu nie są sprzętowym testem odcięcia zasilania. Protokół
korzysta z `fsync`, atomowych rename i trwałości SQLite/DataStore zapewnianej
przez system oraz nośnik; nie naprawia awarii sprzętowych ani utraconych plików.
Jeżeli zapis/odczyt lub odzyskanie jest niemożliwe, blokuje pracę zamiast
potwierdzać sukces. Automatyczne odzyskiwanie dotyczy operacji rozpoczętych
z nowym dziennikiem; beta.2 nie tworzyła tego protokołu.

M6, odsłuch i długie sesje pozostają odrębnymi otwartymi kryteriami.
