# Roadmapa

Każdy etap kończy się weryfikacją i wspólnym przeglądem. Samo przygotowanie
plików nie oznacza przejścia testów ani ukończenia etapu.

| Etap | Zakres | Kryterium odbioru |
| --- | --- | --- |
| M0 — Fundament | Narzędzia, jeden moduł Android, Compose, modele, testy, dokumentacja, CI | Testy JVM i Lint przechodzą, oba debug APK się budują, ekran działa na urządzeniu |
| M1 — Metronom | Scheduler w ramkach audio, PCM, AudioTrack, BPM, metrum, akcenty i start/stop | Brak narastającego dryfu harmonogramu, poprawne akcenty i zmiany BPM na granicy taktu, powtarzalny start/stop, jawne błędy audio |
| M2 — Trening pulsu | Niezależne losowe wyciszenia i cykle taktów słyszalnych/ciszy | Scenariusze 0%, 50%, 100%; poprawne cykle, zegar działa podczas ciszy |
| M3 — Android | Odtwarzanie w tle, powiadomienie, audio focus, wizualny puls i opcjonalne wibracje | Odtwarzanie po blokadzie ekranu, stop z powiadomienia, poprawny cykl życia i obsługa przerwań |
| M4 — Zaawansowany trening | Narastające wyciszenia, zwiększanie i zmniejszanie tempa, podziały rytmiczne i własne akcenty | Np. +10 p.p. wyciszeń/min oraz 60→120 BPM o 5 co 4 takty; jawne rozdzielenie pulsu i podziałów |
| M5 — Dane | DataStore, Room, presety, sesje i dzienne/tygodniowe zestawienia | Lokalny zapis i przywracanie konfiguracji, poprawny czas ćwiczenia i agregacje |
| M6 — Stabilizacja | Pomiary audio, długie sesje, optymalizacja i wydanie 1.0 | Weryfikacja na obu urządzeniach, stabilność BPM i praca bez usług Google, udokumentowane ograniczenia |

## Stan pakietu M2–M5

Zaimplementowano trening ciszy, usługę mediaPlayback, wizualizację i opcjonalne
wibracje, progresję, rampy, podziały/akcenty oraz lokalne dane. W 6/8 BPM
nadal oznacza ósemki, sześć głównych pulsów na takt. Nie ma automatycznego
odtwarzania po ponownym uruchomieniu aplikacji.

Wyniki oraz otwarte próby: [weryfikacja M2–M5](M2-M5-VALIDATION.md).
Kod i testy lokalne nie oznaczają odbioru sprzętowego. Otwarte problemy UI M1,
próby blokady, foreground service i sesja 30-minutowa wymagają osobnego odbioru.
Zatwierdzono przygotowanie odbioru M6, bez nowych funkcji i bez deklaracji
gotowości wydania 1.0. [Checklista odbioru M6](M6-ACCEPTANCE.md).
Plan i przebieg: [implementacja pakietu](IMPLEMENTATION-M2-M5.md).
Użytkownik potwierdził podstawowy odsłuch: dźwięk działa. Pełny odbiór audio,
praca po blokadzie i długi test nadal są otwarte. Zgoda na automatyczne audio
oraz osobno wibracje pozostaje wymagana. [Cicha stabilizacja](QUIET-STABILIZATION.md).
Domyślne testy urządzeniowe nie mogą uruchamiać dźwięku ani wibracji.
Na Samsungu zaliczono pełny dostępny cichy zestaw: 14 testów, w tym 7 UI;
4 testy audio pominięte zgodnie z ograniczeniem. Nie jest to odbiór audio.
Następnie, po osobnej zgodzie, zaliczono dwa krótkie testy z dźwiękiem:
5 cykli Start/Stop oraz obrót/krótkie tło. Bez błędów i pominięć, 9,311 s.
Nie zastępuje to długiej sesji z blokadą, pomiarów ani odbioru na Lenovo.
Historyczny raport fundamentu: [weryfikacja M0](M0-VALIDATION.md).

## Pakiet 0.6 — domknięcie podstaw

Tap tempo, niezależna głośność PCM i trzy syntetyczne barwy; zapis w ustawieniach
i presetach z zachowaniem konfiguracji wersji 1 i schematu Room 1.
Weryfikacja: [raport 0.6](V0.6-VALIDATION.md).
Zaliczono 75 testów JVM, 16 cichych testów Samsunga, Lint bez błędów (4
ostrzeżenia); oba APK zbudowane i zainstalowane bez kasowania danych.
4 testy audio pominięto; odbiór nowych barw pozostaje otwarty.
Test 30-minutowy użytkownik odłożył do własnego użytkowania. Nie jest zaliczony
i nie będzie uruchamiany automatycznie; nie blokuje wersji rozwojowej.
Nowe funkcje nie oznaczają ukończenia M6 ani wydania 1.0.

## Pakiet 0.6.1 — wygoda i diagnostyka

Stały Start/Stop na wszystkich zakładkach, lokalna migawka diagnostyczna kopiowana
wyłącznie na żądanie, skrypt cichej weryfikacji i regresja trwałości plikowej bazy
Room. Bez zmian zegara, formatu ustawień ani migracji. Wyniki:
[raport 0.6.1](V0.6.1-VALIDATION.md). Nie oznacza ukończenia M6.
Lokalnie zaliczono 79 JVM, 7 scenariuszy parsera, Lint bez błędów i oba APK.
Samsung SM-A145R z Androidem 15 zaktualizowany bez kasowania danych:
21 cichych testów zaliczonych, 4 audio pominięte, zero błędów i innych pominięć.
Nie uruchamiano dźwięku ani wibracji; odbiór sprzętowy pozostaje oddzielny.

## Pakiet 0.7 — panel ćwiczeń

Ciemny grafitowo-zielony interfejs na podstawie referencji Metronome Beats,
osobne Ustawienia, Presety z paska, główny ekran z treningiem i układ od 600 dp.
Lampki edytują akcent/ciszę całego pulsu, BPM ma ±1/±5 i edycję liczbową.
Poprawione zaokrąglanie suwaka wyciszeń oraz dokładne ±1%.
Eksport całej historii sesji do lokalnego CSV przez systemowy wybór pliku.
Kodek nadal w wersji 1 z opcjonalnym `beatAccents`; bez migracji Room.
Aktualne wyniki: [raport 0.7](V0.7-VALIDATION.md).
93 JVM PASS, Lint 0/0 w offline, oba APK zbudowane i zainstalowane na Lenovo.
Lenovo po odblokowaniu: 26 cichych testów PASS, 4 audio pominięte,
zero innych pominięć i błędów. Oceniono zrzut UI, poprawiono paletę zaznaczeń,
sprawdzono otwarcie/anulowanie systemowego okna CSV. Testy mają tymczasowe
podtrzymanie ekranu po sprawdzeniu odblokowania; bez zmian ustawień systemu.
Nie zamyka to odbioru M6 ani otwartych testów sprzętowego audio.

## Pakiet 0.7.1 — adaptacyjny tablet i telefon

Cztery układy zależne od okna: telefon pion/poziom, tablet pion/poziom.
Większy instrument i rozwinięty trening na tablecie, kompaktowy nagłówek
telefonu w poziomie. Edycja BPM zachowuje szkic przy klawiaturze i obrocie.
98 JVM, Lint 0/0, parser 7/7, oba APK podpisane i zainstalowane bez kasowania
danych. Samsung i Lenovo: po 28 cichych PASS, 5 audio pominiętych, zero błędów
i innych pominięć. Zrzuty ocenione w obu orientacjach. W kontynuacji nie
uruchamiano audio ani wibracji. Historyczne krótkie audio i otwarte ograniczenia
są oddzielone w [raporcie 0.7.1](V0.7.1-VALIDATION.md). Nie zamyka M6.

## Pakiet 0.8 — publikacja beta

Przygotowane publiczne źródła MIT na GitHubie, krótszy pasek bez nazwy,
README dla użytkowników, stały klucz wydawniczy i ręczny proces tworzący
draft prerelease. Bez sklepów, nowego silnika ani deklaracji M6/1.0.
98 JVM i po 29 cichych testów Samsunga/Lenovo PASS, po 5 audio pominiętych.
Lokalnie offline Lint debug/release 0/0; zdalnie brak błędów i po 3 ostrzeżenia
o nowszych zależnościach. CI i proces wydawniczy zielone, podpisany APK przeszedł
cichą próbę świeżego emulatora API 35 oraz ponowną instalację z zachowaniem BPM.
Po potwierdzeniu kopii klucza przez użytkownika beta została opublikowana
2026-10-20. Publiczny APK jest tym samym sprawdzonym plikiem z draftu.
Stan: [raport 0.8](V0.8-VALIDATION.md).

## Urządzenia testowe

- Samsung SM-A145R, Android 15 / API 35: dotychczasowy telefon testowy.
- Samsung Galaxy A56 5G: urządzenie wskazane w początkowych założeniach, dotąd nietestowane.
- Lenovo TB-X505F: podłączone urządzenie raportuje TrebleDroid_vanilla,
  Android 13 / API 33; pakietu Google Play Services nie wykryto. Wstępnie
  wskazany LineageOS 20 nie jest potwierdzoną nazwą aktualnego obrazu systemu.

Przy testach zapisywać model, wersję Androida, wersję aplikacji i warunki
pomiaru. W późniejszych etapach odróżniać testy PCM/WAV od pomiaru fizycznego
wyjścia audio.
