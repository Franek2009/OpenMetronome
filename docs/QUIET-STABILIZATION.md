# Cicha regresja po 1.0.0

Na prośbę użytkownika usunięto cztery testy sprzętowego odtwarzania oraz dwa
30-minutowe. Nie były to testy niezaliczone: wymagały osobnej zgody i były
pomijane. Ich usunięcie nie stanowi potwierdzenia audio ani zakończenia M6.
Historyczne wyniki pozostają w raportach poszczególnych wydań.

Zachowujemy JVM, PCM offline, atrapy i cichą instrumentację UI/danych.
Runner blokuje sprzętowe audio i wibracje, również dla nieoznaczonego testu.
Domyślne flagi: `allowAudio=false`, `allowHaptics=false`. Nie używamy zerowej
głośności, słuchawek ani Bluetooth jako obejścia.

Testy używają izolowanych danych. Nie czyścimy ani nie odinstalowujemy
aplikacji użytkownika. Dozwolone aktualizacje przez `adb install -r` nie
zastępują osobnej zgody na operacje na fizycznym urządzeniu.

Cicha instrumentacja wymaga odblokowanego ekranu; brak odpowiedniego
środowiska jest pominięciem, nie PASS. Parser raportów nadal wykrywa błędy,
niepełne przebiegi i pominięcia. Usunięcie testów nie wyłącza tej kontroli.

Procedury: [kompilacja](BUILDING.md), [warianty](BUILD-VARIANTS.md),
[odbiór manualny M6](M6-ACCEPTANCE.md). Jitter i latencja fizycznego wyjścia
pozostają niezmierzone. Nowe audio i wibracje wymagają nowej zgody.
