# Wydawanie OpenMetronome

Kanał początkowy: publiczny GitHub Releases. Format instalacyjny Androida:
APK, nie AppImage. Wydanie 0.8.0-beta.1 jest prerelease, nie deklaracją M6/1.0.

## Tożsamość i klucz

Identyfikator aplikacji: `org.openmetronome`. Zmiana nazwy lub ikony nie
zmienia identyfikatora. Oficjalne aktualizacje muszą używać tego samego
klucza podpisu i rosnącego versionCode. Utrata klucza uniemożliwi zwykłe
aktualizacje z GitHuba. Nie używamy klucza debug jako wydawniczego.

Jednorazowe utworzenie (JDK/keytool i openssl):

```bash
source scripts/dev-env.sh
bash scripts/prepare-release-key.sh
```

Pliki `.tooling/signing/openmetronome.p12` i `store.password` mają ograniczone
uprawnienia i są ignorowane przez Git. Skrypt nigdy ich nie nadpisuje.
**Oba pliki wymagają bezpiecznej kopii poza komputerem przed publikacją.**
Nie wklejaj ich ani haseł do czatu, Issue, workflow lub publicznego repozytorium.
Weryfikuj, że kopia pozwala otworzyć magazyn klucza.

W GitHub Secrets ustaw `OM_KEYSTORE_BASE64` (base64 całego magazynu),
`OM_KEYSTORE_PASSWORD`, `OM_KEY_PASSWORD` (przy PKCS12 to samo hasło) oraz
`OM_KEY_ALIAS` (`openmetronome`). Publiczną zmienną `OM_CERT_SHA256` ustaw
na 64-znakowy odcisk SHA-256 certyfikatu, bez dwukropków, małymi literami.
Nie myl go z SHA-256 pliku APK; certyfikat pozostaje ten sam między wydaniami.
Klucz jest używany tylko w zadaniu podpisującym, nie w CI pull requestów.

## Proces

1. Uaktualnij versionName/versionCode w Gradle, changelog i opis w `docs/releases/`.
2. Zatwierdź zmiany na main. CI musi zaliczyć JVM, parsery, Lint debug/release
   oraz kompilację debug/test/release. Release w zwykłym CI jest niepodpisany.
3. Utwórz i wyślij tag `v` + versionName wskazujący aktualny commit main.
4. Ręcznie uruchom **Prepare signed beta release** z gałęzi main i tym tagiem.
   Kod jest przypięty do zweryfikowanego SHA. Proces nie przyjmuje obcego refa
   do zadania z sekretami; sprawdza zgodność tagu z wersją i HEAD main.
5. Workflow ponownie sprawdza projekt, podpisuje APK, odrzuca debug/obcy
   certyfikat i tworzy **draft prerelease**, nigdy nie publikuje samoczynnie.
   Ponowny przebieg nie nadpisuje istniejącego wydania.
6. Osobne zadanie sprawdza podpisany APK na świeżym emulatorze API 35:
   instalacja, zapis BPM i ponowna instalacja z zachowaniem ustawień, bez Start.
   Nie otrzymuje klucza podpisu. Dopiero po tym powstaje draft.
7. Pobierz pliki z draftu, sprawdź SHA256SUMS i podpis. Potwierdź kopię klucza.
   Dopiero wtedy opublikuj prerelease.

Publikowany zestaw: APK, SHA256SUMS, SIGNING-CERT-SHA256.txt oraz opis zmian.
Nie publikuj APK instrumentacji. Opis wydania jest wybierany według versionName.

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
