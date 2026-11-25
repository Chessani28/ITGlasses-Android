# CLAUDE.md — ITGlasses-Android (app)

## Qué es
App Android (Kotlin) de ITGlasses: el celular hace de puente entre los lentes Ray-Ban Meta (como audífonos Bluetooth)
y el backend de `D:\ITGlasses`. Graba voz (PCM16 16 kHz → WAV), la manda al backend para STT, pide la respuesta del
chat y reproduce el audio de Piper. Funciona con la app abierta ("Usar lentes") y con el celular bloqueado mediante un
ForegroundService y el botón de la patilla. Se empareja con el servidor (autoregistro + aprobación desde el panel admin)
por HTTPS usando la CA piloto.

## Estructura
- Proyecto Gradle de un módulo: `settings.gradle`, `build.gradle`, `app/build.gradle`, wrapper Gradle 8.14.1.
- AGP 8.11.1, Kotlin 2.2.10, compileSdk 36, targetSdk 34, Java/JVM 17.
- Paquete `com.itglasses.itglassesandroid`. Dos flavors:
  - `phone` (minSdk 24, sin SDK de Meta) — el que se usa en la demo.
  - `meta` (minSdk 29, sufijo `.meta`, incluye `mwdat-core`).
- `app/src/main/java/...` — lógica: voz (`VoicePipeline`, `VoiceForegroundService`, `BackgroundVoice`, `EndOfSpeech`,
  `GlassesAudio`, `MediaButtonProbe`, `MediaClaim`), emparejamiento (`Pairing*`, `DevicePairing*`), chat
  (`AssistantSession`, `ChatLog`), red (`AuthInterceptor`, `BackendConfiguration`), textos (`*Texts`).
- `app/src/phone`, `app/src/meta` — código propio de cada flavor (`MainActivity`, integración de wearables).
- `app/src/debug` — plantilla `network_security_config.template.xml` y la CA piloto (`res/raw`). El config real se
  genera en build desde `ITGLASSES_PAIRING_ORIGIN` (no hay IP en el repo).
- `app/src/test/...` — pruebas unitarias JUnit 4 (lógica pura, sin dispositivo).
- `ANDROID_META_PREPARATION.md` — notas de Meta, red y ADB.

## Entorno para compilar (PowerShell)
Se necesita **JDK 21** (el `jbr` de Android Studio); con JDK 26 falla.
```powershell
Set-Location D:\ITGlasses-Android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_HOME = 'D:\Android Studio SDK\SDK'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

## Cómo probarlo
```powershell
.\gradlew.bat testPhoneDebugUnitTest testMetaDebugUnitTest
.\gradlew.bat assemblePhoneDebug
```
APK: `app\build\outputs\apk\phone\debug\app-phone-debug.apk`. Instalar con `adb install -r ...` solo si te lo pido
(lo hago yo con el celular conectado). `lintMetaDebug` ya falla por un problema previo
(`app/src/meta/AndroidManifest.xml`, falta `android:exported`): no es culpa de tu cambio; no lo toques salvo que te lo pida.

## Reglas de trabajo
1. **Ramas:** trabaja siempre en una rama nueva `feature/...`, nunca en `master`. Créala desde la rama que yo indique;
   si no digo nada, desde la rama actual. Si hay cambios sin commitear que no son tuyos, avísame antes de cambiar de rama.
2. **Secretos:** no toques `.runtime\api-token.dpapi` ni otros secretos: `local.properties`, `.env`, keystores,
   la CA piloto y sus llaves, tokens o contraseñas. No los leas, copies, muestres ni subas.
3. **Instalaciones:** no instales nada sin preguntarme (dependencias nuevas de Gradle, SDKs, JDK, herramientas).
4. **Git destructivo:** nunca uses `reset --hard`, `clean` ni `force` (incluye `push --force` y `checkout -f`).
   No muevas, borres ni reescribas tags (`demo-lentes-*`) ni los APK de `D:\ITGlasses-APKs`.
5. **No romper la demo:** el modo app abierta + "Usar lentes" y el modo con celular bloqueado deben seguir igual.
   No cambies `applicationId`, la CA piloto ni la config de red sin preguntarme.
6. **Pruebas:** antes de terminar una tarea corre `testPhoneDebugUnitTest testMetaDebugUnitTest` y `assemblePhoneDebug`,
   y arregla lo que falle. Si algo ya fallaba y no tiene que ver con la tarea, dímelo en lugar de cambiarlo a ciegas.
7. **Al terminar:** cuando todo pase, haz commit y push de la rama (`git push -u origin feature/...`, sin force) y dame un
   resumen corto de lo que cambiaste y el resultado de las pruebas.
8. Responde en español y de forma concisa. Los textos que ve el usuario en la app van en español.
