# Wydawanie OpenMetronome

Kanał początkowy: publiczny GitHub Releases. Format instalacyjny Androida:
APK, nie AppImage. Aktualne oficjalne wydanie to
[1.0.0-rc.1 — Prerelease](https://github.com/Franek2009/OpenMetronome/releases/tag/v1.0.0-rc.1),
nie stabilne 1.0. [Checklista RC2 i plan finalnej publikacji](V1.0-RC2-READINESS.md).
Nie uruchamiać tagowania, podpisywania ani publikacji bez osobnej zgody.

## Tożsamość i klucz

Identyfikator aplikacji: `org.openmetronome`. Zmiana nazwy lub ikony nie
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

## Stan wydań i najbliższe metadane

RC1 jest opublikowany: versionCode 16, versionName 1.0.0-rc.1.
[Raport stabilizacji i oficjalnego artefaktu](V1.0-RC1-VALIDATION.md).
Oficjalny certyfikat SHA-256 pozostaje:
`563cd960efc3fc5b483ceae4b96e66728af9e2bb0c52d6162fe37cded72ac4a2`.
Nie tworzyć nowego klucza. Aktualny main zawiera nową ikonę, ale nie podniesiono
wersji i nie zmieniono wydania RC1. Propozycje przyszłych kodów: RC2=17,
stable=18, pod warunkiem sprawdzenia ich dostępności i osobnego zatwierdzenia.

Unsigned CI i próba aktualizacji kopii z podpisem testowym nie są dowodem
zgodności podpisu produkcyjnego. Po utworzeniu oficjalnego draftu wykonać
osobno oryginalne oficjalne APK→oficjalny kandydat na świeżych API 26/35,
bez przepodpisywania, z porównaniem wszystkich danych i bajtów sampli.
Sama ponowna instalacja tej samej wersji w smoke nie zastępuje aktualizacji.

## Proces — wyłącznie po odpowiednim zatwierdzeniu

1. Uaktualnij versionName/versionCode w Gradle, changelog i opis w `docs/releases/`.
2. Zatwierdź zmiany na main. CI musi zaliczyć JVM, parsery, Lint debug/release
   oraz kompilację debug/test/release. Release w zwykłym CI jest niepodpisany.
3. Utwórz i wyślij tag `v` + versionName wskazujący aktualny commit main.
4. Ręcznie uruchom **Prepare signed prerelease** z gałęzi main i tym tagiem.
   Kod jest przypięty do zweryfikowanego SHA. Proces nie przyjmuje obcego refa
   do zadania z sekretami; sprawdza zgodność tagu z wersją i HEAD main.
5. Workflow ponownie sprawdza projekt, podpisuje APK, odrzuca debug/obcy
   certyfikat i tworzy **draft prerelease**, nigdy nie publikuje samoczynnie.
   Ponowny przebieg nie nadpisuje istniejącego wydania.
6. Od 0.9 osobne zadanie quiet instaluje debug i instrumentację na świeżym
   emulatorze API 35. Wykonuje pełny cichy zestaw z allowAudio=false oraz
   allowHaptics=false; parser musi potwierdzić kompletność i brak innych
   pominięć. Skrypt odmawia działania na fizycznym urządzeniu lub emulatorze
   z istniejącą aplikacją. Ten emulator nie służy do testu release.
   Kolejne osobne zadanie sprawdza podpisany APK na drugim świeżym emulatorze API 35:
   instalacja, zapis BPM i ponowna instalacja z zachowaniem ustawień, bez Start.
   Oba zadania emulatorów nie otrzymują klucza podpisu. Dopiero po ich
   zaliczeniu powstaje draft. Ręczny odbiór nowych barw pozostaje oddzielną
   bramką; nie zastępuje go cichy zestaw debug ani smoke podpisanego APK.
7. Pobierz pliki z draftu, sprawdź SHA256SUMS i podpis. Potwierdź kopię klucza.
   Wykonaj także oficjalny→oficjalny upgrade i kontrolę zgodności plików
   draftu z dokładnie testowanym artefaktem. Dopiero po osobnej zgodzie
   opublikuj prerelease; potem sprawdź publiczny plik i status.

Publikowany zestaw: APK, SHA256SUMS, SIGNING-CERT-SHA256.txt oraz opis zmian.
Nie publikuj APK instrumentacji. Opis wydania jest wybierany według versionName.

## Stabilne 1.0.0

Nie wystarczy przemianować APK RC2: nowa wersja i rosnący versionCode wymagają
nowego podpisanego artefaktu z tego samego klucza i ponownej kontroli aktualizacji.
Istniejący workflow zawsze tworzy draft z `prerelease=true`, również dla tagu
`v1.0.0`. Po końcowym odbiorze i osobnej zgodzie publikacja stable wymaga
jawnego `prerelease=false`; nie zmieniamy workflow tylko z powodu nazwy wersji.
Pełna kolejność i kryteria: [RC2 i 1.0](V1.0-RC2-READINESS.md).

## Lokalna kontrola podpisu

Gradle przyjmuje cztery zmienne środowiskowe: `OM_KEYSTORE_PATH`,
`OM_KEYSTORE_PASSWORD`, `OM_KEY_ALIAS`, `OM_KEY_PASSWORD`. Dla oficjalnego
builda użyj `-PrequireReleaseSigning=true :app:assembleRelease`.
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
