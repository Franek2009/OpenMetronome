# Zmiany

## 1.1.0-beta.1 — kandydat do testów użytkownika

- Pełny ekran z dużym BPM, pulsem/taktem, blokadą dotyku i dostępnym Stop.
  Opcjonalne utrzymywanie ekranu oraz ograniczenie migania; bez restartu sesji.
- Procenty 100/75/50/25% oddzielone od bazy 20–300 BPM, dokładny wynik 5–300 BPM.
  Rampa zmienia bazę. Wybór zmiany tempa od taktu lub następnego możliwego pulsu.
- Hybrydowy ekran z szybkimi ustawieniami i rozwijanym treningiem.
- Odliczanie 0/1/2 takty poza czasem praktyki. Metra 5/4, 7/4, 5/8, 7/8
  oraz grupowanie 5/8 i 7/8; dotychczasowe znaczenie BPM w 6/8 zachowane.
- Statystyki: cały czas „Dzisiaj”, Podsumowanie / Historia / Analiza, kalendarz,
  szczegóły sesji, filtry i progi domyślne 20 s/sesję oraz 5 min/aktywny dzień.
  Krótkie sesje pozostają w historii. Nieznany przebieg starych sesji nie jest zerem.
- Room 3: nullable historia rzeczywiście zastosowanej konfiguracji nowych sesji.
  Kopie `.ombak` v2, import v1, osobny eksport zgodny z 1.0 z ostrzeżeniem pominięć.
  CSV zachowuje początkowe kolumny i dodaje mnożnik, wynikowe BPM i stan przebiegu.
- OpenMetronome DEV z odrębnym pakietem, danymi, trwałym podpisem i ikoną DEV.
  Implementacja lokalnych raportów beats/events/audio CSV + metadata JSON w ZIP.
  **Pełna rejestracja sesji jest wyłączona w beta**: kwalifikacja narzutu API26
  niezaliczona. Nie usunięto benchmarku ani nie rozluźniono progu +10%.
  PROD nie zawiera magazynu DEV.
- Poprawione anulowanie obserwatora daty Statystyk: wyjście z ekranu nie zamyka
  kanału równolegle z jego oczekującym odbiorcą.
- Finalizacja usługi czeka na terminalny stan silnika także przy asynchronicznym Stop.
- Sześć starych testów sprzętowego audio usunięto na życzenie użytkownika;
  zachowano testy PCM/atrap i ochronę przed audio oraz wibracjami.
- Metadane: versionCode 19, PROD 1.1.0-beta.1, DEV 1.1.0-beta.1-dev.
  To Prerelease, nie nowa wersja stabilna. Sześć brzmień i ich identyfikatory bez zmian.
  „Drewniany” nadal oczekuje na oryginały i kwalifikację źródeł.

## 1.0.0 — opublikowane stabilne wydanie

- Metadane wersji: versionName 1.0.0, versionCode 18.
- Bez zmian funkcjonalnych względem oficjalnego RC2: ten sam silnik audio,
  brzmienia, logo, Room 2, format `.ombak` v1 i identyfikatory danych.
- Zapisano manualne potwierdzenie aktualizacji, uruchomienia, logo i ogólnego
  działania RC2 na A56; pozostałe scenariusze M6 nie są automatycznie zaliczone.
- Oficjalnie podpisany APK opublikowano po osobnych zgodach i sześciu
  aktualizacjach beta.3/RC1/RC2→1.0 na API26/35. Wyniki i ograniczenia:
  [opis wydania](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.0.0).

## 1.0.0-rc.2 — opublikowany Prerelease

- Nowa ikona użytkownika: adaptive, monochrome Androida 13+ i klasyczne PNG.
  PR #5 scalony; wygląd zaakceptowany, CI na main 5/5 PASS.
- Uporządkowane kryteria RC2 i stabilnego 1.0 oraz dokumentacja odbioru.
- Metadane kandydata: versionCode 17, versionName 1.0.0-rc.2; zachowany pakiet
  i konfiguracja podpisu, bez zmian funkcjonalnych.
- Bez nowych brzmień ani zmian funkcjonalnych. „Drewniany” odłożony do 1.0.1;
  zachowany audyt nie jest blokerem RC2/1.0.0.

## 1.0.0-rc.1 — opublikowany Release Candidate (Prerelease)

[Oficjalne wydanie](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.0.0-rc.1).
To kandydat, nie stabilne 1.0.0.

- Zwolnienie rezerwacji odtwarzania także przy błędzie uruchomienia foreground.
- Końcowy zapis sesji niezależny od zniszczenia usługi; trwała kolejka ponowień
  z odzyskiwaniem po restarcie i zachowaniem identyfikatora sesji.
- Eksport `.ombak` i CSV ponawia zaległe zapisy; przy błędzie nie otwiera pliku
  docelowego i nie zgłasza sukcesu niepełnej historii.
- Rekonstrukcja statystyk dziennych i checkpointów z sesji w starszych `.ombak`
  v1, z limitami odbudowy. Dziennik restore zachowuje dokładne poprzednie dane.
- Testy błędów dysku, cyklu życia foreground i zgodności historycznych kopii;
  baza aktualizacji testowych przesunięta z beta.2 na beta.3.
- Ręczny odbiór około godzinnej praktyki zapisany osobno od automatycznych
  wyników i niezmierzonych parametrów audio. Brak nowych funkcji i barw.

## 0.13.0-beta.3

- Trwały dziennik przywracania: automatyczne cofnięcie przerwanego importu lub
  dokończenie zatwierdzonego zestawu przed udostępnieniem danych i Start.
- Atomowe znaczniki, synchronizacja magazynów i bezpieczne sprzątanie; bez
  destrukcyjnego fallbacku w przypadku nieczytelnego dziennika.
- Rzeczywiste testy zabijania osobnego procesu debug na izolowanych danych,
  także podczas odzyskiwania; usługa probe nie występuje w release.
- Test aktualizacji beta.2→kandydat przez publiczny UI, z porównaniem wszystkich
  danych i sampli. Kopie APK mają jednorazowy podpis testowy, bez oficjalnego klucza.
- CI dla PR i main obejmuje Android 8/API26 oraz API35, ciche testy i aktualizację.
- Akcja klawiatury „Gotowe” w edytorach treningu i nazw presetów kończy fokus,
  zapobiegając ponownemu otwieraniu IME podczas przewijania.

## 0.13.0-beta.2

- Backup wymaga ręcznego Stop i zakończenia zapisu sesji; blokuje równoczesny
  Start i zmiany danych, a eksport obejmuje wcześniejsze zapisy ustawień.
- Przywracanie cofa obsługiwane błędy bazy, ustawień i plików. Awaria cofnięcia
  zachowuje materiały odzyskiwania i jawnie ostrzega o niepełnym stanie.
- Wspólna walidacja sampli, kompletności ZIP i limitów eksportu/importu;
  zachowana zgodność `.ombak` v1. Obrót zachowuje potwierdzenie przywracania.
- Testy integracyjne trwałości, błędów i współbieżności oraz testy systemowego
  pickera na syntetycznym dostawcy dokumentów. Uzupełniona polityka prywatności.
- Bez gwarancji odzyskiwania po zabiciu procesu lub utracie zasilania.

## 0.13.0-beta.1

- Nowy ekran statystyk: karty podsumowania, aktualna i najdłuższa seria,
  średnia sesja, rekord dzienny oraz wykresy 7 dni, 30 dni i 12 tygodni.
- Pełna, wersjonowana kopia `.ombak` obejmująca ustawienia, presety, historię
  oraz własne sample; walidacja i potwierdzenie przed przywróceniem.
- Dotychczasowy eksport CSV pozostaje dostępny jako czytelny raport historii.

## 0.9.0-beta.1 — draft do odsłuchu

- Głośniej domyślnie: wykorzystanie około 89% zakresu PCM, bez dodatkowego trybu.
  Istniejące barwy mają wyższy poziom także w starych presetach. Zacznij odsłuch
  od niższej głośności, zwłaszcza jeśli dotąd była ustawiona na maksimum.
- Pięć nowych własnych barw: Woodblock, Rimshot, Cowbell, Klaśnięcie, Puls elektroniczny.
- Osiem barw z akcentami, zapis w ustawieniach i presetach bez migracji danych.
- Bez zmian zegara i głośności systemowej; brak odsłuchu sprzętowego nowego banku.

## 0.8.0-beta.1

Pierwsze publiczne wydanie testowe na GitHubie, opublikowane 2026-10-20.

- Podpisane APK, sumy SHA-256 i ręcznie uruchamiany proces wydawniczy.
- Krótszy pasek nawigacji bez nazwy aplikacji nad metronomem.
- README dla użytkowników oraz oddzielne instrukcje budowania i publikacji.
- Zachowane funkcje i dane 0.7.1; bez zmian silnika audio.

Wcześniejsze APK debug nie aktualizują się do tego podpisu. Nie odinstalowuj
ich bez zabezpieczenia danych. To beta, nie zakończenie odbioru M6.

## 0.7.1-dev

- Układy telefonu i tabletu w pionie i poziomie.
- Większy instrument i domyślnie rozwinięty trening na tablecie.
- Edytor BPM zachowujący szkic po otwarciu klawiatury i obrocie.

## 0.7.0-dev

- Ciemny panel ćwiczeń, edycja akcentów pulsu, ±1/±5 i wpisywanie BPM.
- Eksport historii do CSV.

Wcześniejsze etapy i wyniki są opisane w [roadmapie](docs/ROADMAP.md).
