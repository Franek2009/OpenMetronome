# Widgety — przygotowanie 1.2

Funkcja na gałęzi rozwojowej; opublikowany APK 1.1.0 jej nie zawiera.
Widgety są rozszerzeniem obecnego interfejsu, bez nowego silnika audio.

## Zestaw

| Widget | Orientacyjny rozmiar | Zawartość |
| --- | --- | --- |
| Mini Metronom | 2×2 | BPM wynikowe, stan, Start/Stop; BPM otwiera metronom |
| Sterowanie metronomem | 4×2, rozszerzalny | BPM, baza i procent, Start/Stop, 100/75/50/25%; przy większej wysokości −1/+1 bazy |
| Statystyki ćwiczeń | 4×2 | Zapisany czas dzisiaj, seria, aktywne dni bieżącego tygodnia; dotknięcie otwiera statystyki |

Dodawanie: przytrzymaj wolne miejsce na pulpicie, wybierz Widgety, a następnie
OpenMetronome. Nazwy i rozmiary siatki zależą od launchera. Obsługujemy Androida 8+;
rozmiar docelowy w komórkach i systemowy podgląd układu są dostępne od Androida 12.
Wszystkie widgety jednej aplikacji sterują tą samą sesją i konfiguracją.
DEV ma osobny pakiet i dane, więc nie steruje zwykłą aplikacją.

## Zachowanie

- Start wznawia odtwarzanie z bieżącą konfiguracją, nie poprzednią przerwaną sesję.
  Ponowne Start podczas uruchamiania/odtwarzania nie tworzy drugiego startu.
- Zmiana procentu zachowuje bazę; −1/+1 zmienia bazę w zakresie 20–300 BPM.
  Tak jak w aplikacji, ręczne −1/+1 wyłącza rampę; procent jej nie wyłącza.
  Wynik może być ułamkowy, od 5 BPM. Obowiązuje ustawienie momentu zmiany tempa.
- Duże BPM pochodzi z zastosowanej konfiguracji. Oczekująca zmiana jest opisana
  strzałką `→` przy wybranej bazie/procencie. Przy 100% bez oczekującej zmiany
  pomocnicza linia znika. Nazwa aplikacji i powtarzany stan Gra/Zatrzymany
  nie zajmują miejsca w widgetach; stan pozostaje dostępny dla TalkBack.
  Widoczne pozostają błędy i przejściowe stany uruchamiania/zatrzymywania.
- Backup i odzyskiwanie zapisów zachowują blokady Start/edycji. Odrzucone polecenie
  informuje o konieczności otwarcia aplikacji. Stop pozostaje dostępny.
- Dzisiejszy czas obejmuje wszystkie zapisane sesje. Seria i aktywne dni respektują
  ustawione progi; brak danych lub błąd odczytu nie jest zerem. Nie ma sekundnika
  ani doliczania jeszcze niezapisanego fragmentu sesji.

## Aktualizacje i ograniczenia

RemoteViews, bez nowych bibliotek. Polecenia używają jawnych, immutable PendingIntent
oraz nieeksportowanego odbiornika, który wiąże istniejącą usługę. Start pozostaje
interakcją użytkownika; odświeżenie lub restart systemu nigdy nie uruchamia audio.
Usługa czeka na wczytanie ustawień przed wykonaniem polecenia z zimnego procesu.
Nie ma operacji widgetów na wątku PCM i nie ma aktualizacji na każde uderzenie.
Błąd komunikacji z launcherem jest logowany i nie propaguje się do usługi audio;
kolejne odświeżenie może ponownie uruchomić obserwator widgetu.

Stan odtwarzania aktualizujemy przy zmianach, z ograniczeniem do około raz na sekundę
w obserwatorze usługi. Statystyki reagują na zapis historii i zmianę progów.
Odświeżenie systemowe ma okres 30 minut; system może je opóźniać. Zmiana daty,
czasu i strefy oraz zmiana rozmiaru również odświeżają widok. Nie używamy dokładnych
alarmów ani dodatkowego wakelocka dla widgetów.

Launcher może chwilowo pokazywać ostatni widok po zabiciu procesu. Nie zapisujemy
flagi „Gra” na dysku i nie wznawiamy automatycznie sesji. Kolejne odświeżenie
lub polecenie uzgadnia widok z bieżącym stanem. Widget nie jest animowanym pulsem
ani pomiarem jittera/latencji.

## Weryfikacja

Regresja obejmuje model tempa, granice i wartości ułamkowe, oczekujące zmiany,
oddzielenie pulsu od projekcji launchera, RemoteViews, prywatność receiverów,
zimne wczytanie konfiguracji, blokadę backupu i pojedynczy Start na atrapie silnika.
Ciche testy API26/35 zapisują screeny UI oraz renderowane podglądy widgetów
z przykładowymi danymi. Te podglądy nie zastępują odbioru w launcherze telefonu.

Do odbioru użytkownika: dodanie/usunięcie i resize w używanym launcherze,
wspólne sterowanie z aplikacją i powiadomieniem, czytelność z dużą czcionką,
zimny proces, zatrzymanie po utracie focus i zachowanie po ponownym uruchomieniu.
Nie zaliczamy tego odbioru ani fizycznego audio na podstawie testów automatycznych.

## Pochodzenie ilustracji README

Zrzuty aplikacji i renderowane zasoby RemoteViews pochodzą z cichych testów PROD
na API35: [przebieg 42529192488](https://github.com/Franek2009/OpenMetronome/actions/runs/42529192488),
commit `020c2ca2d1a081c5f327cad00618f080f9c9ae74`. Interfejs jest po 1.1;
widgety są rozwojową funkcją 1.2. Dane są izolowanymi/przykładowymi danymi testowymi.
Podglądy widgetów nie dokumentują instalacji w launcherze Samsunga.
