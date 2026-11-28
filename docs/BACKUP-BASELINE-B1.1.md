# B1.1 — pomiar bazowy backupu

Punkt odniesienia: `main` `1ff8d94613726f353bc8b49213fa24a04e7165c1`.
Benchmark jest w `androidTest` i mierzy kompletną ścieżkę `AppData`
oraz dodatkowo publiczny odczyt `BackupArchive.read`. Dane zapisuje tylko
w nowym prywatnym katalogu testowym. Standardowy zestaw cichej regresji
wyklucza benchmark. Do kompilacji i uruchomienia służą zwykłe debug APK PROD
i jego odpowiadający APK instrumentacji.

## Odtwarzanie pomiaru

1. Zbuduj `:app:assembleProdDebug :app:assembleProdDebugAndroidTest`.
2. Zainstaluj oba APK na izolowanym emulatorze API 26 lub 35.
3. Uruchom `ANDROID_SERIAL=emulator-5554 bash scripts/measure-backup-baseline.sh`.
   Opcjonalne argumenty, np. `10000 simple`, wybierają jedną kombinację.
   `BACKUP_BASELINE_REPORT_DIR` wskazuje katalog raportów JSON i logu.
   Uruchamiaj po jednej instancji na raz przy porównywaniu wyników.

Profile w obu API mają identyczne dane. Historia zaczyna się w 2020 r. Każdy
48. wpis przecina północ, a osobne wpisy przecinają obie zmiany czasu w strefie
Europe/Warsaw. W prostym profilu jedna czwarta sesji ma pojedynczy punkt
konfiguracji. W gęstym co 12. sesja ma 20 punktów, a co 1000. ma 200 punktów.
Każde 10 000 odtworzeń ma 2000 aktywności, 8000 przedziałów i 100 korekt.
Aktywności są w połowie ręczne i w połowie syntetycznym źródłem FIT z pauzami.
Notatki zawierają UTF-8 i są dłuższe w profilu gęstym. Co dziesiąty rekord
aktywności i korekty jest oznaczony jako usunięty. Każda baza ma też 32
presety i 32 kanoniczne WAV-y o maksymalnej długości aplikacji.

### Metodyka

- Przygotowanie fixture obejmuje fizyczny plik Room, WAL checkpoint,
  ustawienia, sample i małą poprawną kopię kontrolną. Czas osobnego
  oszacowania SQL odpowiada pierwszym bezpiecznikom produkcyjnego backupu.
- Czas do pierwszego otwarcia docelowego pliku to wewnętrzne przygotowanie
  eksportu, w tym kodowanie i wbudowana walidacja. Czas walidacji w tabeli
  oznacza drugi, osobny publiczny odczyt i sprawdzenie liczników oraz WAV-ów.
- Restore udanego eksportu odtwarza tę samą historię po kontrolnej zmianie
  ustawienia. Test weryfikuje liczniki, ustawienie i dokładne bajty sampli.
  Przy odmowie eksportu przywracamy małą, poprawną kopię w tej samej dużej
  bazie. Wynik przed `PREPARED` rozróżnia błąd pojemności bieżącej migawki
  od awarii późnej fazy.
- Czas rezerwacji jest mierzony od wywołania operacji do zaobserwowania
  zwolnienia `backupWorking`. Przy otwarciu dokumentu eksportu i po
  przygotowaniu restore test sprawdza rezerwację Start bez uruchamiania
  audio. Przy restore sprawdza też blokadę edycji ustawień. Po ostatniej
  próbie w każdej bazie wykonuje produkcyjny końcowy zapis syntetycznej
  sesji i weryfikuje jej trwały zapis w Room.
- Pierwsza próba dla danej bazy odbywa się bez ekranu aplikacji. Druga
  uruchamia rzeczywiste Activity i czeka na podsumowanie w zakresie
  „Cała aktywność”. To porównanie opisowe, bo próby odbywają się w jednej
  kolejności i współdzielą rozgrzanie procesu. Przy restore widoczny ekran
  reaguje na zmiany w bazie i rezerwację, tak jak w aplikacji.
- Sterta to `totalMemory - freeMemory`, PSS to `Debug.getPss()` dla procesu
  instrumentowanej aplikacji. Próbki są pobierane co około 50 ms oraz na
  granicach faz i checkpointach. Maksima są obserwowane, więc krótszy skok
  może wypaść między próbkami. Operacje mają po jednej próbie na konfigurację.

## Wyniki

Na obu API wykonano sześć konfiguracji, każdą bez otwartego ekranu aplikacji i z
otwartymi Statystykami. Wszystkie **12 scenariuszy** dało oczekiwany wynik.
Cztery próby prostego profilu 10 tys. zakończyły się pełnym eksportem i
restore. Pozostałe 20 prób eksportu i 20 odpowiadających prób restore
odmówiły zgodnie z limitami, przed otwarciem dokumentu wyjściowego lub przed
`PREPARED`. Po każdym scenariuszu udało się zapisać jeszcze jedno odtworzenie.

Gęsty profil 10 tys. przekroczył łączną kontrolę 16 MiB (historia 15,76 MiB,
szacunek łączny 16,34 MiB). Dla 50 i 100 tys. pierwszym ograniczeniem była
wielkość samej historii. Profil 100 tys. ma też 102 083 dniówki, 20 000
aktywności i 80 000 przedziałów, więc przekracza limity ich liczebności
niezależnie od bajtów. Szacunek SQL nie obejmuje całego archiwum.

Gotowe migawki prostego profilu 10 tys. miały 298 647 B przed i 298 660 B po.
Maksymalny obserwowany WAL wyniósł ok. 9,69 MiB, a dziennik z plikami
roboczymi ok. 0,81 MiB. Sam rozmiar skompresowanego ZIP-a zaniża więc potrzebę
miejsca. Statystyki przy 100 tys. otwierały się w 7–9 s, a PSS podczas prób
z ekranem sięgało 311 MiB, chociaż te kopie odmawiały przed dużą alokacją
archiwum. B1.2 trzeba oceniać również z aktywnym UI.

Pomiary są z emulatorów x86_64 i debug PROD. Limit sterty wynosił 512 MiB
na obrazie API 26 i 192 MiB na obrazie API 35. Wyniki nie są miarodajnym
porównaniem szybkości samych wersji Androida. Po pierwszej macierzy wykonano
ponownie wszystkie scenariusze z granicą czasu restore ustawioną tuż przed
rezerwacją oraz pomiarem obu migawek. Tabele zawierają drugą macierz.
Istniejące testy `BackupStorageTest` i `BackupUiTest` przeszły po 19/19 na
obu API. Sprawdzono też, że filtr cichej regresji nie uruchamia benchmarku.

### Czasy i wyniki

Czasy w sekundach. Para oznacza brak UI / otwarte Statystyki. Próby bez archiwum nie miały fazy osobnej walidacji. Kolumny eksport i restore podają czas rezerwacji. Przy odmowie restore źródłem była mała poprawna kopia.

| API | Odtw. / profil | Przygotowanie danych | Kontrola SQL | Przygot. eksportu do otwarcia pliku | Eksport / odmowa | Walidacja | Restore / odmowa | Gotowe migawki restore |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 26 | 10 tys. / prosty | 2.21 | 0.011 / 0.011 | 6.166 / 5.810 | 6.177 / 5.823 | 2.276 / 2.267 | 12.629 / 12.829 | 11.490 / 11.630 |
| 26 | 10 tys. / gęsty | 2.34 | 0.014 / 0.016 | — | 0.017 / 0.017 | — | 0.019 / 0.032 | — |
| 26 | 50 tys. / prosty | 7.33 | 0.039 / 0.039 | — | 0.035 / 0.041 | — | 0.035 / 0.524 | — |
| 26 | 50 tys. / gęsty | 8.84 | 0.089 / 0.084 | — | 0.077 / 0.067 | — | 0.065 / 0.562 | — |
| 26 | 100 tys. / prosty | 14.07 | 0.083 / 0.085 | — | 0.062 / 0.075 | — | 0.061 / 0.874 | — |
| 26 | 100 tys. / gęsty | 17.36 | 0.141 / 0.134 | — | 0.121 / 0.126 | — | 0.116 / 0.830 | — |
| 35 | 10 tys. / prosty | 2.88 | 0.010 / 0.018 | 7.452 / 6.743 | 7.456 / 6.748 | 2.003 / 2.028 | 16.430 / 16.628 | 14.666 / 14.824 |
| 35 | 10 tys. / gęsty | 3.23 | 0.018 / 0.017 | — | 0.019 / 0.022 | — | 0.021 / 0.022 | — |
| 35 | 50 tys. / prosty | 11.43 | 0.044 / 0.046 | — | 0.034 / 0.078 | — | 0.035 / 0.187 | — |
| 35 | 50 tys. / gęsty | 12.45 | 0.076 / 0.103 | — | 0.070 / 0.119 | — | 0.070 / 0.318 | — |
| 35 | 100 tys. / prosty | 21.36 | 0.091 / 0.095 | — | 0.063 / 0.075 | — | 0.063 / 0.376 | — |
| 35 | 100 tys. / gęsty | 23.09 | 0.136 / 0.266 | — | 0.127 / 0.224 | — | 0.123 / 0.563 | — |

### Pamięć i miejsce

Wartości w MiB. Pary dotyczą operacji kopii bez UI / przy otwartych Statystykach. Maksima sterty i PSS są niezależne. Dla przypadków odrzuconych brak wyjściowego ZIP-a i dziennika restore jest oczekiwany.

| API | Odtw. / profil | Szacunek SQL | Baza | Szczyt WAL | ZIP | Szczyt dziennika i plików roboczych | Szczyt sterty | Szczyt PSS | Otwarcie statystyk (s) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 26 | 10 tys. / prosty | 6.74 | 9.64 | 9.69 | 0.28 | 0.81 | 49.7 / 83.2 | 110.4 / 158.4 | 2.41 |
| 26 | 10 tys. / gęsty | 16.34 | 20.54 | 0.68 | 0.00 | 0.00 | 3.5 / 27.0 | 59.8 / 107.4 | 2.99 |
| 26 | 50 tys. / prosty | 33.70 | 47.85 | 0.60 | 0.00 | 0.00 | 3.4 / 62.2 | 59.4 / 189.9 | 4.78 |
| 26 | 50 tys. / gęsty | 81.69 | 102.34 | 0.68 | 0.00 | 0.00 | 3.8 / 61.1 | 59.9 / 195.0 | 4.42 |
| 26 | 100 tys. / prosty | 67.40 | 95.71 | 0.62 | 0.00 | 0.00 | 4.0 / 85.6 | 59.9 / 276.1 | 8.85 |
| 26 | 100 tys. / gęsty | 163.37 | 204.73 | 0.70 | 0.00 | 0.00 | 4.1 / 75.2 | 60.2 / 262.9 | 9.07 |
| 35 | 10 tys. / prosty | 6.74 | 9.64 | 9.69 | 0.28 | 0.81 | 70.2 / 87.1 | 193.3 / 235.5 | 2.43 |
| 35 | 10 tys. / gęsty | 16.34 | 20.54 | 0.68 | 0.00 | 0.00 | 40.4 / 29.9 | 138.5 / 167.0 | 2.56 |
| 35 | 50 tys. / prosty | 33.70 | 47.85 | 0.64 | 0.00 | 0.00 | 5.8 / 54.0 | 105.0 / 236.0 | 3.76 |
| 35 | 50 tys. / gęsty | 81.69 | 102.34 | 0.68 | 0.00 | 0.00 | 5.1 / 56.0 | 104.4 / 238.1 | 3.92 |
| 35 | 100 tys. / prosty | 67.40 | 95.71 | 0.64 | 0.00 | 0.00 | 5.1 / 61.5 | 104.3 / 304.7 | 6.97 |
| 35 | 100 tys. / gęsty | 163.37 | 204.73 | 0.69 | 0.00 | 0.00 | 4.7 / 67.7 | 104.1 / 311.0 | 7.60 |

## Warunki dla B1.2 i B1.3

- W B1.2 porcjować odczyt Room i zapisywać kopię v3 do prywatnego pliku.
  Walidator i enkoder mają rozliczać tę samą strukturę, ze ścisłymi limitami
  rzeczywistych bajtów, rekordów i pól. Nie wystarczy przeniesienie samego ZIP-a
  z RAM na dysk. Ponowić te same profile i porównać fazy oraz przyrost sterty.
  Podwyższoną pojemność udostępnić dopiero po potwierdzeniu, że produkcyjny
  restore i dziennik potrafią bezpiecznie odtworzyć takie kopie.
- Do sprawdzenia pojemności użyć agregatów SQL na żądanie i licznika enkodera
  przy eksporcie. Wynik można zapamiętać i unieważniać przy zapisach. Ścieżka
  zapisu sesji nie może skanować historii ani wymagać wolnego miejsca na
  przyszły backup. Podgląd pojemności ma odróżniać brak pomiaru, zbliżający
  się limit, przekroczenie i brak miejsca na dysku. Próg ostrzegania ustalić
  względem zmierzonych marginesów. Interfejs pozostaje do uzgodnienia.
- B1.3 ma zweryfikować i utrwalić **obie** migawki, pliki, ich katalogi i
  manifest z rozmiarami, SHA-256 i rolami. Wersjonowany marker `ready`
  wiąże sprawdzony manifest. Robocza SQLite ma mieć sprawdzony checkpoint
  i zamknięte połączenia lub jawnie ujęty trwały WAL. Trwałe migawki powinny
  pozostać w prywatnym katalogu dziennika, poza katalogiem cache. Sposób zapisu markera
  to plik tymczasowy, fsync, atomowe przeniesienie i fsync katalogu.
  Stary dziennik nadal odczytujemy według jego istniejącej wersji.
- Przed `ready` rezerwować rzeczywiście przydzielone bloki dla najbardziej
  kosztownego odtworzenia, WAL oraz małych zapisów markerów i katalogów.
  Wysokość rezerwy oprzeć na pomiarach restore i rollbacku, także przy
  większej migawce **przed**. Przy `ENOSPC` po `ready` odzyskiwanie może
  zwolnić rezerwę, lecz zachowuje manifest i obie migawki do trwałego
  zakończenia. Sprawdzić przerwanie po zwolnieniu rezerwy i przed cofnięciem.
  Gdy inny proces zużyje odzyskane miejsce, dziennik ma pozostać do
  ponowienia po zwolnieniu miejsca. Sam odczyt wartości wolnych bloków
  i utworzenie pliku rzadkiego nie zapewniają rezerwy.
- Publiczny import nadal normalizuje dniówki i `checkpointAt`, a rollback
  odtwarza dokładny zapis. Scenariusz docelowy to pełny round-trip 100 tys.
  odtworzeń z dodatkowymi aktywnościami. Pomiary B1.2/B1.3 na obu API mają
  uzasadnić końcowe limity, w tym osobne miejsce na rollback.
