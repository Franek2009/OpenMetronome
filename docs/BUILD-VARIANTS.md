# Warianty OpenMetronome

Warianty rozdzielają dane i podpisy zwykłej aplikacji oraz DEV. W 1.1 beta DEV
ma wyłączoną pełną rejestrację sesji: nie zaliczono kwalifikacji narzutu API26.
Zwykłe funkcje i statystyki działają, istniejące raporty można eksportować.
[Integracja i granice](V1.1-DEV-INTEGRATION-VALIDATION.md).

| Wariant | Pakiet | Nazwa | Podpis |
| --- | --- | --- | --- |
| prodRelease | org.openmetronome | OpenMetronome | dotychczasowy oficjalny, gdy jawnie udostępniono OM_* |
| prodDebug | org.openmetronome.debug | OpenMetronome | lokalny debug |
| devRelease | org.openmetronome.dev | OpenMetronome DEV | osobny trwały DEV, gdy udostępniono OM_DEV_* |
| devDebug | org.openmetronome.dev.debug | OpenMetronome DEV | lokalny debug |

Namespace kodu pozostaje `org.openmetronome`; nazwy klas runnera/Activity nie
zmieniają się razem z applicationId. Każdy pakiet ma własny UID, Room,
DataStore, bibliotekę sampli i dzienniki odzyskiwania. Nie kopiujemy automatycznie
danych produkcyjnych. Provider fixture ma authority `${applicationId}.backup-documents` oraz tytuł
zawierający applicationId, aby picker rozróżniał oba debug flavory.
Testy każdego debug flavoru nadal korzystają z izolowanego magazynu testowego.

Beta: PROD `1.1.0-beta.1`, DEV `1.1.0-beta.1-dev`, oba versionCode 19.
Nie podmieniamy APK ani tagu stabilnego 1.0.0.

## Kompilacja bez kluczy

```bash
./gradlew :app:testProdDebugUnitTest :app:testDevDebugUnitTest \
  :app:lintProdDebug :app:lintProdRelease :app:lintDevDebug :app:lintDevRelease \
  :app:assembleProdDebug :app:assembleProdDebugAndroidTest \
  :app:assembleDevDebug :app:assembleDevDebugAndroidTest \
  :app:assembleProdRelease :app:assembleDevRelease
python3 scripts/verify-build-variants.py
```

Skrypt inspekcji wymaga `ANDROID_HOME` i Javy w PATH. Sprawdza wszystkie cztery
rzeczywiste APK, identyfikatory, etykiety, wersje, authority, cele instrumentacji
oraz brak testowych komponentów w obu release. Odmawia przy kilku plikach APK
w jednym katalogu wariantu, aby stary podpisany plik nie maskował nowego unsigned.

APK: `app/build/outputs/apk/<prod|dev>/<debug|release>/`.
Instrumentacja: `app/build/outputs/apk/androidTest/<prod|dev>/debug/`.
Unsigned release nie nadaje się do instalacji. Nie używaj ogólnego
`assembleRelease` do oficjalnego podpisywania: buduje więcej niż jeden flavor.

## Klucze

Produkcja zachowuje `OM_KEYSTORE_PATH`, `OM_KEYSTORE_PASSWORD`, `OM_KEY_ALIAS`,
`OM_KEY_PASSWORD` oraz kontrolę `-PrequireReleaseSigning=true`.
DEV ma niezależne `OM_DEV_KEYSTORE_PATH`, `OM_DEV_KEYSTORE_PASSWORD`,
`OM_DEV_KEY_ALIAS`, `OM_DEV_KEY_PASSWORD` i `-PrequireDevSigning=true`.
Niekompletna konfiguracja jest błędem. Brak kompletu zmiennych pozostawia
odpowiedni release niepodpisany; nie ma fallbacku do drugiego klucza ani debug.
Debug korzysta z klucza debug także wtedy, gdy skonfigurowano podpis flavoru.

Na zatwierdzenie użytkownika przygotowano trwały certyfikat DEV i osobne sekrety
`OM_DEV_*`; publiczny fingerprint jest w pliku DEV-SIGNING-CERT-SHA256.txt wydania.
Prywatny klucz i hasła nie należą do Git ani artefaktów. Lokalną kopię klucza
należy zachować w bezpiecznym backupie. PROD zachowuje dotychczasowy certyfikat.
Oficjalny workflow ma dwa oddzielne zadania podpisania i nigdy nie podaje
sekretów PROD zadaniu DEV. Zwykłe CI nie dostaje żadnego klucza wydawniczego.

## Ciche emulatory i izolacja

CI uruchamia pełną instrumentację obu debug flavorów na API26 i API35.
`quiet-emulator.sh prod|dev` instaluje wyłącznie właściwy pakiet i runner,
przy `allowAudio=false`, `allowHaptics=false`. Skrypt wymaga świeżego emulatora
bez wskazanego wariantu; nie odinstalowuje ani nie czyści istniejących danych.

Po obu zestawach `verify-variant-isolation.sh official-1.0.0.apk` sprawdza SHA
oraz certyfikat oryginalnego stabilnego APK, instaluje go obok obu debug flavorów,
porównuje UID i dostęp do prywatnych plików testowych. Sprawdza byte-for-byte
zainstalowany APK produkcji. Nie uruchamia zwykłej aplikacji ani odtwarzania.
Odmawia pracy na fizycznym urządzeniu lub przy istniejącej instalacji produkcji.
Jest to test izolacji pakietów; nie test migracji danych między nimi.

`verify.sh` domyślnie buduje/testuje prodDebug. Wskazanie `--device` nie zastępuje
wymaganej zgody na operacje na fizycznym urządzeniu. Nie uruchamia audio.
Przy aktualizacji starego debug pakietu bez suffixu nie odinstalowuj go ani
nie czyść danych: nowy prodDebug instaluje się osobno, a stare dane pozostają.

CI aktualizacji produkcji nadal używa testowo przepodpisanych kopii beta.3
oraz unsigned prodRelease. To nie jest próba aktualizacji oficjalny→oficjalny;
ten etap nie uruchamia oficjalnego podpisywania.

Raport i ujawnione niepowodzenia pierwszych prób: [walidacja](V1.1-BUILD-VARIANTS-VALIDATION.md).
