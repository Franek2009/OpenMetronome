# OpenMetronome — zasady pracy

- Kontynuacja 2026-10-20: przekazane przez użytkownika zasady zamykają zgodę
  na audio. Nie uruchamiać kolejnych prób audio ani wibracji bez nowej zgody.
  Historycznie, przed tą kontynuacją, w pierwszej iteracji 0.7.1 na Samsungu
  zaliczono trzy krótkie testy audio (19,004 s); na Lenovo ich nie wykonano.
  To nie jest wynik końcowej poprawki UI. Szczegóły: docs/V0.7.1-VALIDATION.md.
  Podłączenie ani „kontynuuj” nie rozszerzają zgody.
- Historyczne dwa testy audio Samsunga w 0.5 zaliczono: 9,311 s, zero pominięć.
- Użytkownik odłożył test 30-minutowy do własnego zwykłego użytkowania.
  Nie uruchamiaj go automatycznie i nie oznaczaj jako zaliczony. Nie blokuje
  dalszego rozwoju wersji dev. Nowe barwy/głośność testuj przez PCM offline;
  odsłuch sprzętowy nowych funkcji wymaga nowej zgody. Domyślnie audio i wibracje
  instrumentacji mają pozostać zablokowane.
- Nie używaj zerowej głośności, słuchawek ani Bluetooth jako obejścia.
  Ciche testy to JVM/PCM offline, atrapy i instrumentacja bez odtwarzania.
- `allowAudio=true` wymaga nowej zgody i jawnego wyboru uzgodnionych metod;
  nie uruchamiaj całego zestawu z tą flagą. `allowHaptics=false`.
  Ochrona aplikacji testowej nie chroni ręcznie uruchomionej aplikacji zwykłej.
- Użytkownik potwierdził: „Przetestowałem, dźwięk działa”. Podstawowy odsłuch
  jest potwierdzony przez użytkownika, bez szczegółów BPM, trybów i pomiarów.
  Pełny odbiór audio pozostaje otwarty. Historyczne próby nie obejmowały długiego
  testu ani wibracji i nie stanowią pomiaru fizycznej precyzji wyjścia audio.
- Testy danych muszą używać izolowanej bazy i preferencji. Nie czyść ani nie
  odinstalowuj aplikacji użytkownika; aktualizuj APK przez `adb install -r`.
- Oddzielaj wdrożony kod, zaliczone testy, testy pominięte i odbiór ręczny.
  Nie deklaruj ukończenia M6 lub gotowości 1.0.

Procedura i bramki: [cicha stabilizacja](docs/QUIET-STABILIZATION.md).
Odbiór M6: [checklista sprzętowa](docs/M6-ACCEPTANCE.md).
