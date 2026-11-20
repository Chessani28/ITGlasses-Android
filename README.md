# ITGlasses Android

App Android del asistente **ITGlasses**: hablas (con el micrófono de los lentes
Ray-Ban Meta o del teléfono), el backend transcribe, el asistente responde con
manuales, conocimiento general o internet, y Piper lee la respuesta.
Backend: [ITGlasses](https://github.com/Chessani28/ITGlasses).

## Flujo de voz

1. Grabación: mono, 16 kHz, máximo 30 s. Si los lentes están conectados por
   Bluetooth y la casilla **"Usar micrófono de los lentes"** está marcada, se usa
   su micrófono (Android 12+); si no, el del teléfono.
2. `POST /api/v1/voice/transcribe`: WAV → texto.
3. `POST /api/v1/assistant/chat`: el asistente completo (manuales, historial,
   web). Texto y voz comparten la conversación (`session_id`), así que funcionan
   los seguimientos como "¿y el siguiente paso?".
4. `POST /api/v1/voice/synthesize`: respuesta → audio, que se reproduce como
   multimedia (también por las bocinas de los lentes).

## Sabores (flavors)

| Sabor | Para qué | Mín. Android |
|---|---|---|
| `phone` | Solo teléfono; no requiere el SDK de Meta | 7.0 (API 24) |
| `meta` | Agrega registro y sesión con Meta Wearables (DAT) | 10 (API 29) |

`targetSdk` 36, lo que exige Google Play desde el 31/08/2026.

## Compilar

```powershell
.\gradlew.bat assemblePhoneDebug        # APK en app\build\outputs\apk\phone\debug\
.\gradlew.bat testPhoneDebugUnitTest    # pruebas unitarias
```

El sabor `meta` necesita el SDK `com.meta.wearable:mwdat-core`, que no está en
google() ni en mavenCentral(); se compila en la PC que lo tiene configurado.

**Sin PC:** GitHub Actions compila el sabor `phone` en cada push. La APK se
descarga en la pestaña **Actions** → última ejecución → `itglasses-phone-debug`
(se conserva 14 días).

## Conectar con el backend

- **Debug con cable:** por defecto apunta a `http://127.0.0.1:8000`. Con el
  teléfono por USB, `adb reverse tcp:8000 tcp:8000` redirige ese puerto a la PC.
- **Red local:** escribe la URL (por ejemplo `http://192.168.1.50:8000`) y el
  token en la app. En debug solo se aceptan direcciones privadas o loopback.
- **Emparejamiento por dispositivo:** la app ya tiene su parte (llave en
  AndroidKeyStore, código de aprobación, peticiones firmadas). Las rutas del
  servidor (`/pairing/requests`, `/pairing/status`, `/pairing/claim`) todavía no
  están en el backend de GitHub.

Gradle properties opcionales: `ITGLASSES_DEBUG_BACKEND_URL`,
`ITGLASSES_PRODUCTION_BACKEND_URL` y `ITGLASSES_PAIRING_ORIGIN` (HTTPS).

## Permisos

- `RECORD_AUDIO`: se pide al grabar por primera vez.
- `MODIFY_AUDIO_SETTINGS`: para usar el micrófono Bluetooth de los lentes.
- `BLUETOOTH_CONNECT` (solo el sabor meta): para el registro con Meta AI.

Más detalle de la integración con Meta en
[ANDROID_META_PREPARATION.md](ANDROID_META_PREPARATION.md).
