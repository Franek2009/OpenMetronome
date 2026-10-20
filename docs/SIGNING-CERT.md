# Publiczny certyfikat wydań

Identyfikator: `org.openmetronome`. Podpis: RSA 4096, CN=OpenMetronome.
Odcisk SHA-256 certyfikatu oficjalnych wydań:

```text
563cd960efc3fc5b483ceae4b96e66728af9e2bb0c52d6162fe37cded72ac4a2
```

To informacja publiczna, nie klucz prywatny. Odcisk APK zmienia się przy każdej
kompilacji; odcisk tego certyfikatu powinien pozostać stały.
Sprawdzenie: `apksigner verify --verbose --print-certs OpenMetronome-VERSION.apk`.
Debug APK ma inny certyfikat i nie jest oficjalnym wydaniem.
