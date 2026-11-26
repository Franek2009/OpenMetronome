# Grupowanie odtworzeń w sesje ćwiczeniowe 1.3

`AppData.practiceGrouping` wylicza grupy z utrwalonych odtworzeń o dodatnim
czasie. Wywołujący przekazuje chwilę obliczenia. Zmiana progu przelicza grupy
na żądanie. W tle pracuje dotychczasowy zapis odtworzeń.

## Reguły

- Kolejność wyznacza `startedAt`, a przy tej samej chwili UUID w porządku
  tekstowym. Koniec odtworzenia to `startedAt + durationMs`. Zmiana zegara
  może dać nakładkę albo odtworzenie zapisane później z wcześniejszym
  początkiem. Odtworzenia nadal są widoczne z utrwalonym czasem.
- Próg to 5, 15, 30 lub 60 minut albo wyłączone grupowanie automatyczne.
  Domyślnie 30 minut włącznie. Przerwę liczymy od **najpóźniejszego końca**
  w grupie. Przy wyłączonym progu każde odtworzenie tworzy osobną grupę,
  chyba że działa jawna korekta.
- Grupa jest zakończona, gdy wszystkie jej odtworzenia są zakończone i
  wyznaczono kolejną grupę, istnieje jawny koniec albo upłynął próg przerwy.
  Przy wyłączonym progu zakończone odtworzenia wystarczają. Przekroczenie
  progu rozpoznaje następny odczyt. Cofnięcie zegara może odsunąć automatyczne
  zakończenie ostatniej grupy do ponownego przekroczenia progu.
- Czas metronomu w grupie jest sumą utrwalonych czasów jej odtworzeń.
  Rozpiętość obejmuje czas od pierwszego początku do najpóźniejszego
  końca, wraz z przerwami. Średnia czasu metronomu obejmuje zakończone
  grupy i przypisuje je do dnia rozpoczęcia. Brak zakończonych grup daje
  brak średniej. Statystyki metronomu, unia aktywności muzycznych, cele
  i serie pobierają nadal te same surowe dane.

## Jawne decyzje i podgląd

- Koniec zapisuje granicę za wskazanym UUID po Stop, finalizacji rekordu i
  opróżnieniu zaległego zapisu. Powtórzenie obowiązującego zakończenia jest
  operacją pustą. Jeśli nowsze łączenie przesłoniło zakończenie, można je
  jawnie zapisać ponownie. Rozdzielenie zachowuje granicę przed wskazanym
  UUID. Łączenie sąsiednich grup zapisuje pierwszy UUID lewej i ostatni UUID
  prawej grupy. Obejmuje cały zakres między nimi po zmianie lub wyłączeniu
  progu, również jeśli w środku pojawi się późno zapisane odtworzenie.
- Na każdej granicy wygrywa ostatnia jawna decyzja o największym numerze
  porządkowym. Zegar urządzenia służy do daty znacznika usunięcia. Gdy
  brakuje kotwicy, reguła nie wpływa na bieżący podział, ale pozostaje w kopii.
- Lokalny reset oznacza jako usunięte także przesłonięte korekty dotykające
  wnętrza lub granic wybranej grupy. Reset globalny obejmuje wszystkie
  aktywne korekty. Dotychczasowy próg pozostaje ustawieniem automatycznego
  grupowania po resecie.
- Podgląd zawiera grupy zmieniające się przed i po oraz liczbę resetowanych
  korekt. Usunięcie szerokiej korekty może zmienić inne grupy i podgląd
  obejmuje także te skutki. Wywołujący przekazuje zaakceptowany podgląd do
  zapisu. Globalny reset używa tego samego dwuetapowego wywołania i wymaga potwierdzenia w
  przyszłym UI. Aplikacja ponownie oblicza konsekwencje w transakcji. Zmiana
  progu, późny zapis lub inna korekta mogą unieważnić podgląd. Zapis decyzji
  i oznaczanie usunięć nie modyfikują surowych rekordów odtworzeń.

## Zgodność

Zachowujemy dokładny ostatni schemat Room 4 z `main`. Addytywna migracja 4→5
tworzy tabelę korekt oraz unikatowy indeks numeru porządkowego. Test obejmuje
aktualizację bazy z istniejącą historią, aktywnością i jej przedziałem. Dla
wcześniejszych szkiców Room 4 sprzed zamrożenia nadal obowiązuje ścieżka
eksportu z danego buildu i importu pełnej kopii.

W pełnym backupie v3 pole preferencji zapisuje także domyślny próg. Opcjonalna
sekcja `GRP1` po notatkach zapisuje decyzje i znaczniki usunięcia. Przywrócone
korekty z wcześniejszego draftu zachowują zakres zapisany w ich dwóch UUID.
Czytnik akceptuje poprzednie układy v3, także z notatkami, oraz v1/v2. Starszy czytnik
testowy wymaga aktualizacji do odczytu sekcji `GRP1`. Import zastępuje rekordy
grupowania. Archiwa bez korekt przywracają pusty zestaw korekt, a kopie bez
ustawienia progu zachowują bieżący próg. Eksporty kompatybilne v1/v2 pomijają
nowe dane. Pełna kopia wraz z migawkami cofania i ponowienia obejmuje ustawienie
i korekty w ramach dotychczasowego limitu 16 MiB.

Regresja obejmuje granice progu, zegar, nakładki, późny zapis, konflikty,
reset w wielu partiach SQL, ponowne otwarcie bazy i przerwanie procesu podczas
przywracania. Test skali przelicza 55 000 odtworzeń i 12 000 nakładających się
korekt. Interfejs zarządzania grupami pozostaje kolejnym etapem.
