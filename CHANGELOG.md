# Zmiany

## 0.13.0-beta.3 — przygotowywana

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
