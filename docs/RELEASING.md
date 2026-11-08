# Wydawanie OpenMetronome

Kanał początkowy: publiczny GitHub Releases. Format instalacyjny Androida:
APK, nie AppImage. Bieżące wydanie i linki sprawdzamy w
[README](../README.md) i [GitHub Releases](https://github.com/Franek2009/OpenMetronome/releases/latest).
Opublikowane pliki i tagi pozostają niezmienne.
Nie uruchamiać tagowania, podpisywania ani publikacji bez osobnej zgody.

## Tożsamość i klucz

Oficjalny wariant: `prodRelease`. Identyfikator aplikacji: `org.openmetronome`. Zmiana nazwy lub ikony nie
zmienia identyfikatora. Oficjalne aktualizacje muszą używać tego samego
klucza podpisu i rosnącego versionCode. Utrata klucza uniemożliwi zwykłe
aktualizacje z GitHuba. Nie używamy klucza debug jako wydawniczego.

Historyczna procedura utworzenia klucza (wyłącznie dla nowego projektu;
**nie uruchamiać w celu przygotowania RC2/1.0**, użyć istniejącego klucza):

```bash
source scripts/dev-env.sh
bash scripts/prepare-release-key.sh
```

Pliki `.tooling/signing/openmetronome.p12` i `store.password` mają ograniczone
uprawnienia i są ignorowane przez Git. Skrypt nigdy ich nie nadpisuje.
**Oba pliki wymagają bezpiecznej kopii poza komputerem przed publikacją.**
Nie wklejaj ich ani haseł do czatu, Issue, workflow lub publicznego repozytorium.
Weryfikuj, że kopia pozwala otworzyć magazyn klucza.
Sam magazyn jest chroniony losowym hasłem, ale store.password zawiera je
wprost. Przejęcie obu plików pozwala podpisywać fałszywe aktualizacje.
Kopię całego folderu przechowuj na bezpiecznym nośniku, najlepiej w szyfrowanym
archiwum lub magazynie z osobnym hasłem, którego nie zapisujesz obok kopii.

W GitHub Secrets ustaw `OM_KEYSTORE_BASE64` (base64 całego magazynu),
`OM_KEYSTORE_PASSWORD`, `OM_KEY_PASSWORD` (przy PKCS12 to samo hasło) oraz
`OM_KEY_ALIAS` (`openmetronome`). Publiczną zmienną `OM_CERT_SHA256` ustaw
na 64-znakowy odcisk SHA-256 certyfikatu, bez dwukropków, małymi literami.
Nie myl go z SHA-256 pliku APK; certyfikat pozostaje ten sam między wydaniami.
Klucz jest używany tylko w zadaniu podpisującym, nie w CI pull requestów.

## Metadane następnego wydania

Przed nowym wydaniem sprawdź rzeczywisty main, publiczne wydania, drafty i tagi.
Ustal rosnący wolny versionCode oraz zatwierdzony versionName; nie wnioskuj
z historycznego opisu poprzedniego RC. Zachowaj istniejący certyfikat SHA-256:
`563cd960efc3fc5b483ceae4b96e66728af9e2bb0c52d6162fe37cded72ac4a2`.
Nie twórz nowego klucza. Opublikowane APK i tagi są niezmienne.

Unsigned CI i próba aktualizacji kopii z podpisem testowym nie są dowodem
zgodności podpisu produkcyjnego. Po utworzeniu oficjalnego draftu wykonać
osobno oryginalne oficjalne APK→oficjalny kandydat na świeżych API 26/35,
bez przepodpisywania, z porównaniem wszystkich danych i bajtów sampli.
Sama ponowna instalacja tej samej wersji w smoke nie zastępuje aktualizacji.

## Proces — wyłącznie po odpowiednim zatwierdzeniu

1. Przygotuj razem versionName/versionCode, changelog i szkic opisu w
   `docs/releases/`, przed końcową regresją.
2. Przed tagowaniem sprawdź pełne zielone CI dokładnego SHA main: scope=full,
   JVM, parsery, Lint, buildy, PCM oraz ciche testy i aktualizacje API26/35.
   Jeśli ostatni wynik to docs-only, uruchom `gh workflow run android.yml --ref main`
   i sprawdź nowy headSha oraz wszystkie wymagane zadania. Zielone docs-only
   nie wystarcza. [Zakres CI i bramka](CI.md). Release w zwykłym CI jest niepodpisany.
3. Utwórz i wyślij tag `v` + versionName wskazujący aktualny commit main.
4. Po osobnej zgodzie ręcznie uruchom **Prepare signed prerelease** z gałęzi main
   i tym tagiem. Dla 1.1.0 ustaw `include_dev=false` (wyłącznie PROD).
   Domyślne `true` zachowuje wcześniejszy proces obu wariantów.
   Kod jest przypięty do zweryfikowanego SHA. Proces nie przyjmuje obcego refa
   do zadania z sekretami; sprawdza zgodność tagu z wersją i HEAD main.
5. Workflow ponownie sprawdza projekt, podpisuje APK, odrzuca debug/obcy
   certyfikat i tworzy **draft**, nigdy nie publikuje samoczynnie.
   Wersja bez sufiksu ma `prerelease=false`; beta/RC mają `prerelease=true`.
   Ponowny przebieg nie nadpisuje istniejącego wydania.
6. Osobne zadania quiet uruchamiają oba debug flavory na świeżych API26/35,
   z allowAudio=false i allowHaptics=false. Parser wymaga kompletu bez pominięć.
   Kolejne świeże emulatory API26/35 sprawdzają wybrane podpisane release:
   instalacja, zapis BPM i ponowna instalacja z zachowaniem ustawień, bez Start.
   Zadania emulatorów nie otrzymują kluczy. Dopiero po ich zaliczeniu powstaje
   draft. Odbiór fizycznego audio i nowych barw pozostaje osobną bramką.
7. Pobierz pliki z draftu, sprawdź SHA256SUMS i podpis. Potwierdź kopię klucza.
   Wykonaj także oficjalny→oficjalny upgrade i kontrolę zgodności plików
   draftu z dokładnie testowanym artefaktem. Dopiero po osobnej zgodzie
   opublikuj wydanie z właściwym statusem; potem sprawdź publiczny plik i status.

Publikowany zestaw beta: dwa APK, SHA256SUMS i dwa fingerprinty certyfikatów
oraz opis zmian.
Nie publikuj APK instrumentacji. Opis wydania jest wybierany według versionName.

## Stabilne 1.0.0

Nie wystarczy przemianować APK RC2: nowa wersja i rosnący versionCode wymagają
nowego podpisanego artefaktu z tego samego klucza i ponownej kontroli aktualizacji.
Historyczny proces 1.0 tworzył draft z `prerelease=true`; publikacja stable
po osobnej zgodzie zmieniła status na `prerelease=false`. Obecny helper
rozpoznaje sufiks wersji, zawsze zachowując `draft=true`.
Pełna kolejność i kryteria: [RC2 i 1.0](V1.0-RC2-READINESS.md).

## Lokalna kontrola podpisu

Gradle przyjmuje cztery zmienne środowiskowe: `OM_KEYSTORE_PATH`,
`OM_KEYSTORE_PASSWORD`, `OM_KEY_ALIAS`, `OM_KEY_PASSWORD`. Dla oficjalnego
builda użyj `-PrequireReleaseSigning=true :app:assembleProdRelease`.
Bez kompletu zmiennych taki build kończy się błędem, nie podpisem debug.
Nie podawaj haseł jako argumentów poleceń. Przed pakowaniem ustaw publiczne
`OM_CERT_SHA256` i uruchom `bash scripts/package-release.sh`.

## Istniejące instalacje debug

Podpis debug i wydawniczy są różne przy tym samym identyfikatorze. Android
odmówi aktualizacji. Nie obchodzimy tego przez odinstalowanie ani czyszczenie
danych użytkownika. CSV nie jest pełną kopią zapasową. Weryfikację oficjalnego
APK wykonujemy na osobnym emulatorze; migracja instalacji roboczych wymaga
osobnego ustalenia i nie jest częścią pierwszego wydania.

## Sklepy później

Nie tworzymy kont ani nie ponosimy kosztów automatycznie. Google Play wymaga
m.in. konta osoby pełnoletniej, opłaty rejestracyjnej 25 USD, weryfikacji,
podpisanego AAB, opisu/grafik, klasyfikacji treści, deklaracji uprawnień,
polityki prywatności i Data safety. Nowe osobiste konta (po 2023-11-13)
wymagają 12 testerów zapisanych przez 14 kolejnych dni przed wnioskiem
o dostęp do produkcji. Wymagania trzeba ponownie sprawdzić przy zgłoszeniu:
[konto](https://support.google.com/googleplay/android-developer/answer/6112435),
[testy](https://support.google.com/googleplay/android-developer/answer/14151465),
[Data safety](https://support.google.com/googleplay/android-developer/answer/10787469).
Przed Play ustalimy Play App Signing tak, aby nie utracić zgodności podpisów
z GitHubem; nie zakładamy, że dowolny klucz uploadowy to klucz aplikacji.

F-Droid wymaga osobnego zgłoszenia i konfiguracji budowania; przyjęcie nie
jest automatyczne. Zgodność aktualizacji między kanałami zależy od podpisów.
Również dystrybucja poza Play podlega ewoluującym zasadom Androida:
[weryfikacja deweloperów](https://developer.android.com/developer-verification).

## DEV i izolacja wariantów (1.1 beta)

[Warianty](BUILD-VARIANTS.md) rozdzielają pakiety, dane i trwałe certyfikaty.
Workflow **Prepare signed prerelease** sprawdza dokładny tag na HEAD main,
ponawia JVM/Lint/PCM/buildy i ciche testy obu flavorów API26/35, po czym tworzy
wyłącznie draft. Przy `include_dev=true` dwa osobne zadania podpisują prodRelease i devRelease;
sekrety OM_* oraz OM_DEV_* nigdy nie są mieszane ani zastępowane.

`package-release.sh` domyślnie akceptuje wyłącznie PROD i jego certyfikat.
DEV wymaga jawnego `--dev`, pakietu org.openmetronome.dev, wersji z -dev
oraz OM_DEV_CERT_SHA256. Zmiana nazwy pliku nie obchodzi walidacji.
Zadania bez kluczy sprawdzają wybrane podpisane APK na świeżych API26/35, bez Start.
Przy `include_dev=true` draft ma dwa APK, SHA256SUMS i dwa fingerprinty.
Przy `false` zadanie podpisania DEV jest pomijane i nie otrzymuje sekretów.
Draft PROD zawiera dokładnie trzy pliki: APK, SHA256SUMS i
SIGNING-CERT-SHA256.txt. `scripts/create-release-draft.sh` sprawdza wejściowe
sumy i składa pliki bez budowania, podpisywania lub nadpisywania wydania.

Przed publikacją pobierz ponownie draft i artefakty, porównaj wszystkie bajty,
metadane, manifest/uprawnienia i zawartość DEX. Sprawdź podpis/certyfikat,
aktualizację oficjalny 1.0→beta oraz świeżą instalację i aktualizację DEV na obu API.
Skrypt `verify-upgrade.sh --official old.apk new.apk` wymaga znanych SHA-256 obu
plików (OFFICIAL_BASELINE_SHA256 i OFFICIAL_CANDIDATE_SHA256), certyfikatu PROD
i czystego emulatora. Nie używa klucza ani przepodpisywania. `--official-dev`
sprawdza import danych i ponowną instalację tego samego DEV z jego certyfikatem.
Dla oficjalnej beta.1 (code 19) użyj `--official-beta`; dla 1.0 (code 18)
`--official`. Oba tryby wymagają rosnącego versionCode i oryginalnych APK.
Dla 1.1.0 wymagane są cztery aktualizacje: obie bazy na API26 oraz API35.
Porównujemy ustawienia, presety, historię, statystyki i dokładne bajty sampla.
Domyślny tryb CI nadal testuje przepodpisane kopie z jednorazowym kluczem;
nie zastępuje to kontroli oficjalnych podpisów po przygotowaniu draftu.

Zaktualizuj opis draftu do rzeczywistych wyników. Publiczna beta ma
`prerelease=true`, `draft=false`, `make_latest=false`; 1.0.0 pozostaje latest stable.
Po publikacji anonimowo pobierz wszystkie pliki, porównaj sumy i podpisy, sprawdź
linki README. Stable 1.1 wymaga osobnej zgody i odbioru manualnego nowej wersji.
Nigdy nie obejdź niezgodnego podpisu odinstalowaniem lub czyszczeniem danych.
