# Raporty DEV — zdarzenia i ZIP v1 (etap 3b)

Zależność: PR #15 / `69500178ae71be1f234491eb48b48fd0518d25c9`.
Ten etap przygotowuje zdarzenia, korelację, eksport i zarządzanie raportami.
**Aplikacja nie przekazuje jeszcze obserwatorów do silnika. Rejestracja sesji
nie jest aktywowana.** W DEV można odczytać raporty przygotowane przez narzędzia
lub instrumentację. Kolejny PR połączy te elementy po kwalifikacji narzutu.

## Granice wątków i zamykania

PCM zachowuje istniejący bufor SPSC. `TraceCapture` dodaje osobną kolejkę
128 rekordów sterowania MPSC; nigdy nie wywołujemy jej z renderowania PCM.
Kolejka nie nadpisuje rekordów. Liczniki prób i strat są osobne dla kanałów.
Rezerwacja transportu 4 MiB obejmuje wszystkie aktywne/zamykane sesje,
IO i kanał sterowania w istniejącym zapasie 512 KiB/sesję. To nie limit
PSS całego procesu; pomiary ART nadal są osobną bramką.

Start/config/Stop mają opcjonalny, chroniony przed Exception port
`ControlObservation`. Wyjątek obserwatora nie zatrzymuje odtwarzania.
`failures` tego portu musi zostać uwzględnione przez integrację przed
ogłoszeniem kompletności raportu. Zatrzymanie i zwalnianie zasobów nie czekają
na callback Stop; test zatrzymanego obserwatora sprawdza tę kolejność.

Android focus: żądanie/wynik/zwolnienie trafiają na PCM worker do jego bufora;
asynchroniczne focus/noisy na Main trafiają do portu sterowania po żądaniu Stop.
Nie ma wywołania MPSC z workera audio. Nie zmieniono polityki focus ani
harmonogramu odtwarzania. Rzeczywiste odtworzenie/focus sprzętowe nie jest
walidowane przez ciche testy.

Właściciel wywołuje `finishControls()` dopiero po zakończeniu poleceń i zapisu
historii. Zamknięcie PCM nie zamyka automatycznie nowego kanału: historia może
zostać powiązana później. Integracja musi zsynchronizować ostatnie callbacki
poleceń z zamknięciem sterowania, także przy Stop wywołanym spoza Main.
Stary interfejs `start(engineId)` zachowuje automatyczne zamykanie pustego
kanału sterowania i działa z dotychczasowymi fixture.

## Korelacja i przechowywanie

Klucz: losowy UUID instancji silnika + lokalny numer sesji. UUID raportu jest
wyliczany deterministycznie z tego klucza. Ponowny start usługi z numerem 1
nie wskazuje starego raportu. Licznik silnika obejmuje teraz także odrzuconą
próbę pozyskania biblioteki sampli; nie zmienia to UUID historii ani PCM.

Opcjonalny callback `SessionRecorder` podaje CREATED, CHECKPOINT_REQUESTED,
CHECKPOINT_RETURNED i CHECKPOINT_FAILED oraz istniejący UUID historii.
RETURNED oznacza powrót funkcji checkpointu, **nie dowód trwałego zapisu**:
AppData może zachować zapis do ponowienia. Awaria callbacku nie odrzuca historii.
Integracja komunikatów błędów/retry AppData i finalizacji usługi pozostaje
w kolejnym PR. Nie zmieniono Room ani formatu `.ombak`.

`records.bin` i `control.bin` używają tego samego ograniczonego framingu OMD1
z CRC, ale mają niezależne sekwencje. Jedna rezerwacja dyskowa i jeden limit
512 MiB obejmują obydwa pliki oraz manifest. Stare dzienniki bez control.bin
pozostają eksportowalne, z jawnym brakiem kanału i liczników. Nie dopisujemy zer
za brakującą historię zdarzeń. Limity 2 GiB łącznie i brak automatycznego
kasowania pozostają w mocy.

## Publiczny ZIP v1

Dokładnie cztery wpisy UTF-8: `beats.csv`, `events.csv`, `audio.csv`,
`metadata.json`. Eksport strumieniowy, bez lokalnej kopii ZIP.

Nagłówek CSV:

```text
source,sequence,nanos,kind_code,kind,frame,a,b,c,d,config
```

- CSV cytuje wszystkie wartości i podwaja cudzysłowy; konfiguracja to pełny
  ConfigCodec v1 z newline wewnątrz cytowanego pola. Wiersze kończy CRLF.
- source to pcm/control; sequence jest lokalne dla kanału, nie dla pliku.
- nanos to monotoniczne nanosekundy obserwacji, frame to ramka mono PCM;
  puste frame oznacza niedostępność. Żaden z tych czasów nie mierzy głośnika.
- beats.csv: PCM BEAT (6), także wyciszone i akcentowane sloty.
- events.csv: PCM 1/3/4/5/14/15/16/17/18/19/20 oraz sterowanie. PCM jest
  wypisywane przed sterowaniem; plik nie udaje globalnie posortowanego czasu.
- audio.csv: pozostałe rekordy PCM, w tym wszystkie próby/częściowe zapisy.
- metadata.json: schemaVersion=1 (liczba), liczby całkowite 64-bitowe,
  flagi bool, nieznane pomiary/parametry liczbowe null. Słowniki kodów,
  przyczyny i jednostki dotyczą dokładnie danego builda.

Kody PCM 1–17 zachowano. Dodano 18 FOCUS_REQUESTED, 19 FOCUS_RESULT,
20 FOCUS_RELEASE; parametr a przechowuje surowy wynik, gdy istnieje.
ERROR: a=AudioFailure, b=surowy kod, c=1 jeżeli kod jest dostępny.
WRITE_THROW: a=żądana liczba, b=offset, c=surowy kod, d=flaga dostępności.
Flaga 0 oznacza brak kodu; numerycznego placeholdera 0 nie interpretujemy
jako rzeczywistego wyniku. AndroidPcmSink zachowuje ujemne kody platformy.

Sterowanie: 100 Start, 101 odrzucenie, 102 Stop, 103 żądanie konfiguracji;
110–114 focus/noisy, 120–123 historia/checkpoint, 130–132 usługa/tło,
140–141 odzyskiwanie/błąd zapisu. Adapter Androida zapisuje wynik focus na
PCM (18–20); asynchroniczne zmiany/noisy używają 112/114. Pozostałe wejścia
przygotowano dla integracji, nie są deklaracją ich aktywacji w aplikacji.
Historia: a/b to bity UUID (signed Long), c=finished, d=interrupted.

`complete` oznacza kompletność **rejestracji**, nie udane ćwiczenie, poprawny
zapis historii ani zaliczone audio. Raport może zawierać poprawnie zapisany
błąd silnika. Uszkodzony koniec, brak pliku, niepoprawne liczniki lub straty
powodują oznaczenie incomplete/interrupted; kompletne rekordy nadal są
eksportowane. Nie naprawiamy ani nie kasujemy źródeł podczas eksportu.

## DEV UI i automatyzacja

W Ustawieniach DEV: odświeżanie stron po 50 raportów, stan i rozmiar,
powiązanie historii, eksport systemowym wyborem pliku i usuwanie wyłącznie
wybranego UUID po potwierdzeniu. Raporty są osobne od historii praktyki.
Produkcja ma pusty punkt kompozycji, bez ekranu i implementacji magazynu.
Instrumentacja ma odrębny katalog danych, nie katalog istniejącej instalacji.

Operacje wymagają zakończonych producentów i zatrzymanego odtwarzania.
Eksport i usuwanie są wzajemnie wykluczone. Start nie czeka na eksport:
może odrzucić rejestrację diagnostyczną z jawnym statusem, a odtwarzanie działa.
Eksport sprawdza rozpoczęcie odtwarzania i anulowanie między rekordami.
Anulowanie nie przerywa siłowo blokującego wywołania dostawcy dokumentów;
czeka na jego powrót. Plik docelowy może pozostać częściowy, źródło jest zachowane.
Usuwanie po potwierdzeniu nie ma funkcji cofnięcia i nie oferuje anulowania.

Analiza gotowego eksportu, bez odtwarzania:

```bash
python3 scripts/analyze-dev-trace.py raport.zip
```

Analizator sprawdza strukturę, kody, rozdział kanałów, kolejność w plikach,
liczby rekordów i jawne straty. Brakujący licznik zostawia jako null.
Nie zastępuje testów dokładności PCM ani pomiaru fizycznego wyjścia.
CI kopiuje wyłącznie ZIP syntetycznego fixture z nowego testu instrumentacyjnego
DEV i uruchamia na nim analizator. Raport to `trace-export-fixture.zip`
plus `trace-export-summary.json` w artefakcie API26/35. Bez danych użytkownika.

## Walidacja i otwarte bramki

JVM: MPSC/overflow, późna historia, restart silnika, cztery pliki ZIP i dokładna
konfiguracja, anulowanie/błąd celu, konflikt z usuwaniem, limit po usunięciu,
uszkodzony/brakujący plik, niepoprawne liczniki i stary dziennik PCM-only.
Common: wyjątek callbacku historii, zachowany UUID przy ponowieniu, native
error code z flagą dostępności, izolacja awarii i zablokowanego obserwatora,
rozdział kanałów focus na atrapach. Android: ZIP/historia/odczyt/usuwanie
fixture oraz lista UI i anulowanie potwierdzenia usuwania.

Test błędnych końcowych liczników najpierw wykazał FAIL: eksport błędnie
zachowywał complete. Poprawiono walidację liczników względem rekordów i
nieznanych wartości. Końcowe wyniki dokładnego SHA podajemy w PR po odczycie CI.

Nadal nie ma aktywnej rejestracji każdej sesji, integracji usług/storage ani
kwalifikacji całego toru na ART (p99 +10%, brak strat, pamięć). Przegląd
redundancji testów nastąpi po tej integracji. Brak tagowania, publikacji,
operacji na telefonach, fizycznego audio lub wibracji. Jitter/latencja niezmierzone.
