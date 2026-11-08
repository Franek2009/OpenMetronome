# CI: zakres kontroli i przygotowanie wydania

## Wybór zakresu

Workflow **Android verification** uruchamia się dla PR, push na main oraz
ręcznie. Zawsze pokazuje końcową kontrolę **CI result**.

| Zmiana / zdarzenie | Zakres |
| --- | --- |
| Wyłącznie README.md, CHANGELOG.md, PRIVACY.md lub Markdown w docs/ | docs |
| Aplikacja, zasoby, źródła sampli, skrypty, workflow, Gradle, schematy, inne ścieżki | full |
| Zmiany mieszane, pusty diff, brak wiarygodnego porównania | full |
| Ręczne workflow_dispatch, również po zmianie samej dokumentacji | full |

Lista lekka jest zamknięta: np. grafika w docs/images/ albo Markdown w assets/
nie są skrótem docs. Zmiana typu pliku lub nadanie Markdownowi prawa wykonania
wybierają full. Przeniesienia sprawdzają starą i nową ścieżkę.

Dla PR porównujemy merge-base SHA bazy i dokładnego HEAD PR. Dla push
sprawdzamy cały zakres before → after, nie tylko ostatni commit. Nieprawidłowy
lub niedostępny zakres wybiera full. Awaria samego narzędzia blokuje CI result;
nie może dać pozornego sukcesu dokumentacji.

## Lekka ścieżka

Bez JDK, SDK, Gradle, kompilacji APK i emulatorów. Uruchamia testy polityki,
`git diff --check` i kontrolę lokalnych odnośników w zmienionych Markdownach.
Po usunięciu lub przeniesieniu pliku sprawdza też odnośniki do starej ścieżki
w niezmienionych dokumentach. Nie wymusza naprawiania niezwiązanych z tym
historycznych placeholderów.

Sprawdzane są odnośniki inline, referencyjne, obrazy i HTML href/src.
Przykłady w blokach kodu, kod inline i komentarze HTML są pomijane. Kontrola
sprawdza pliki, nie kotwice nagłówków. Nie pobiera zewnętrznych URL, więc
przejściowy błąd sieci nie blokuje PR dokumentacyjnego. Publiczne pliki wydania
sprawdzamy osobno po publikacji.

W podsumowaniu Android ma **NOT RUN — docs-only**. To nie jest PASS JVM,
PCM lub instrumentacji ani dowód pełnej regresji. Przy braku porównania
lokalne linki mają jawne NOT RUN, a aplikacja wymaga full.

## Pełna ścieżka i końcowa bramka

Pozostają JVM PROD/DEV, Lint debug/release, buildy, PCM offline, izolacja,
ciche API26/35 oraz aktualizacje API26/35. Bez fizycznego audio lub wibracji.

CI result wymaga sukcesu kontroli zakresu i lekkich kontroli. W full także
verify, obu macierzy quiet i upgrade. Failure, cancelled, brak wyniku lub
nieoczekiwane skipped blokują sukces. W docs wymagane zadania Androida są
pominięte zgodnie z zakresem i oznaczone NOT RUN. Nie opisujemy lekkiego
przebiegu jako dawnego „5/5 pełnej regresji”.

Artefakty ci-scope i ci-result zawierają SHA, zakres, powód, zmienione ścieżki,
wynik linków i stan bramki. Rzeczywiste czasy zadań są w Actions.
Nie zmieniamy automatycznie ochrony gałęzi. CI result jest stałą kontrolą
do oceny przed scaleniem; dotychczasowe nazwy zadań Androida pozostają.

## Zasady pracy i wydania

- Najpierw zbieramy wersję, changelog i szkic opisu. Potem pełna regresja
  dokładnego HEAD, scalenie oraz kontrola właściwego zakresu nowego main.
- Przed tagiem wymagane jest **pełne zielone CI dokładnego SHA main**.
  Jeśli ostatni automatyczny przebieg był docs, uruchom ręcznie:

  ```bash
  gh workflow run android.yml --ref main
  ```

  Sprawdź headSha, scope=full i wszystkie wymagane zadania PASS. Sam zielony
  CI result w docs nie wystarcza. Zgoda na tag, podpis i publikację pozostaje osobna.
- SOURCE_REVISION w APK pozostaje SHA źródła. Nie osłabiamy identyfikacji buildu
  w celu poprawienia cache; lekki zakres w ogóle nie buduje aplikacji.
- Lokalnie wykonujemy potrzebne testy zmiany i reprodukcję błędu. Pełny zestaw
  zaliczony w CI tego samego HEAD nie wymaga bezpowodnego powtarzania lokalnie.
- Jeden podpisany artefakt służy do kontroli, aktualizacji i publikacji.
  Błąd procesu nie uruchamia automatycznie nowego podpisania. Awarię zachowujemy
  jako FAIL; sposób odzyskania i jego zatwierdzenie muszą być jawne.
- Po publikacji aktualizujemy status i linki ścieżką docs. Weryfikacja publicznych
  plików nadal obejmuje anonimowe pobranie, SHA-256, certyfikat i identyczność.
- Aktualizacje dla użytkownika: przy zmianie etapu, błędzie lub potrzebnej
  decyzji; przy długim oczekiwaniu krótki status. Po scaleniu i kontroli CI
  usuwamy gałąź po sprawdzeniu zachowania commitów, bez force-delete.

Procedura i ograniczenia: [wydawanie](RELEASING.md),
[ciche testy](QUIET-STABILIZATION.md), [mapa kodu](CODEBASE_MAP.md).
Automatyczne wznawianie podpisywania/draftu pozostaje poza tym wdrożeniem.
