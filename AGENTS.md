# AGENTS.md

Guía para asistentes de IA (Claude Code, Codex, Copilot, etc.) que trabajen en este repositorio.
Las instrucciones para personas están en `README.md`.

## Proyecto

**Grabadora Agente**: app Android de una sola pantalla. Graba audios, los organiza en carpetas
(borrar, mover, renombrar, compartir) y tiene un "modo agente" que escucha una palabra clave
sin internet y graba automáticamente. Su prioridad es **no estar activa 24/7**.

- Lenguaje: **Kotlin**. UI con **vistas XML clásicas** y `findViewById`. **No** usa Compose ni ViewBinding.
- Paquete / applicationId: `com.grabadora.agente`
- minSdk 26 · targetSdk/compileSdk 35 · AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.10.2 (wrapper)
- Interfaz y comentarios **en español**.
- Documentación técnica completa: `docs/DOCUMENTACION-TECNICA.md` (actualízala si cambias el flujo, los datos o los ajustes).
- El código sigue *El gran libro de Android* (8.ª ed.). Los comentarios `Cap. N` señalan de qué
  capítulo sale cada técnica. Mantén ese estilo didáctico.

## Comandos

```bash
# Requiere JDK 17 o 21 (Gradle 8.10 NO funciona con JDK 22+) y ANDROID SDK (platforms;android-35)
./gradlew assembleDebug        # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug            # debe terminar sin errores (los avisos se toleran)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s KeywordService   # logs del servicio de palabra clave
```

Modelo de voz (no está en el repo, está en `.gitignore`):
`powershell -ExecutionPolicy Bypass -File scripts/descargar-modelo.ps1`. Lo descarga en
`app/src/main/assets/model-es/` y genera el archivo `uuid` que Vosk necesita.

No hay tests automatizados todavía. La verificación mínima es que `assembleDebug` y `lintDebug` pasen.

## Estructura

```
app/src/main/java/com/grabadora/agente/
├─ AudioApp.kt              Application: crea el AudioRepository único
├─ data/
│  ├─ Audio.kt              data class de un audio
│  ├─ AudioDbHelper.kt      SQLiteOpenHelper, tabla "audios"
│  └─ AudioRepository.kt    carpetas = directorios reales + metadatos en SQLite; LiveData `cambios`
├─ record/Recorder.kt       envoltorio de MediaRecorder (AAC/.m4a, amplitud para detectar silencio)
├─ agent/
│  ├─ KeywordService.kt     servicio en primer plano (tipo microphone): Vosk → graba → vuelve a escuchar
│  ├─ AgentState.kt         estado global (LiveData) compartido por servicio, UI y mosaico
│  ├─ Prefs.kt              lectura tipada de SharedPreferences (claves de xml/preferencias.xml)
│  ├─ AgentTileService.kt   mosaico de ajustes rápidos
│  └─ ArmarActivity.kt      actividad invisible que arranca el servicio desde el mosaico
└─ ui/
   ├─ MainActivity.kt       lista, carpetas, grabación manual, reproducción, menús, permisos
   ├─ AudioAdapter.kt       RecyclerView.Adapter
   └─ SettingsActivity.kt   PreferenceFragmentCompat
```

## Reglas y decisiones que hay que respetar

1. **Un solo dueño del micrófono a la vez.** Vosk (`SpeechService`, usa AudioRecord) y `MediaRecorder` no
   pueden usarlo a la vez. El servicio llama a `detenerEscucha()` antes de grabar y vuelve a llamar a
   `escuchar()` al terminar. La grabación manual está bloqueada mientras `AgentState.activo` sea verdadero.
2. **Nada de 24/7.** El servicio:
   - devuelve `START_NOT_STICKY`;
   - se apaga por inactividad (`minutos_inactividad`);
   - se pausa con `ACTION_SCREEN_OFF`;
   - se apaga con `ACTION_BATTERY_LOW` y `ACTION_POWER_DISCONNECTED`, según los ajustes.

   No añadas arranque al encender el teléfono (BOOT_COMPLETED), alarmas que lo reactiven ni `START_STICKY`
   sin que el usuario lo pida explícitamente.
3. **Restricciones de Android 14+:** un servicio en primer plano de micrófono solo puede arrancar mientras la
   app está visible. Por eso el mosaico abre `ArmarActivity` (translúcida) en lugar de arrancar el
   servicio directamente. Cada `startForegroundService()` debe ir seguido de `ServiceCompat.startForeground()`,
   también si el servicio ya estaba activo.
4. **Archivos:** los audios viven en `getExternalFilesDir(DIRECTORY_MUSIC)/Grabaciones/<carpeta>/`. No
   requieren permisos de almacenamiento. Para compartir se usa `FileProvider` (autoridad
   `${applicationId}.fileprovider`, rutas en `res/xml/file_paths.xml`). Si cambias la ruta base, actualiza
   también `file_paths.xml`.
5. **Toda operación de archivos pasa por `AudioRepository`.** Mantiene sincronizados el disco y SQLite, y
   emite `cambios` para que la UI se refresque. `listar()` limpia los registros cuyo archivo ya no existe.
   Mover y renombrar nunca sobrescriben: usan `archivoLibre()`.
6. **Palabra clave:** la gramática de Vosk es `[palabraClave, "[unk]"]`. Las palabras deben existir en el
   vocabulario del modelo `vosk-model-small-es-0.42`. Si no existen, Vosk las ignora sin avisar.
7. **Textos visibles** siempre en `res/values/strings.xml`, en español.
8. **Iconos de notificación y del mosaico** (`ic_mic`, `ic_stop`) **sin** `android:tint="?attr/..."`: la
   bandeja del sistema no resuelve atributos del tema. En los layouts usa `app:tint`, porque lint marca
   error con `android:tint`.
9. **Nombres de carpeta y archivo** siempre pasan por `AudioRepository.nombreValido` (también la carpeta de voz
   en `Prefs`). Nada de rutas armadas con texto del usuario sin validar.
10. **Disco y SQLite fuera del hilo principal** en la UI: `MainActivity.recargar()` usa el hilo `io`. La lista es
    un `ListAdapter`: actualízala con `submitList`, no con `notifyDataSetChanged`.
11. **Servicio:** `liberar()` solo quita sus propios `Runnable`; no uses `removeCallbacksAndMessages(null)` (perdería
    el aviso de `cargarModelo` y el `Model` nativo quedaría abierto). La carga lleva un número de `generacion`.
12. **Base de datos:** `onUpgrade` nunca borra la tabla; agrega migraciones por versión.
13. **Respaldo desactivado** (`allowBackup="false"` + `data_extraction_rules.xml`). No lo actives: son grabaciones de voz.
14. **No subas versiones** de AGP, Kotlin ni Gradle por tu cuenta. Gradle 9 y AGP 9 cambian bastante cosas.
   Las dependencias de librerías sí se pueden actualizar si compila y lint pasa.

## Pendientes / ideas

- Exportar o copiar audios a una carpeta pública (MediaStore) para que no se pierdan al desinstalar.
- Tests unitarios para `AudioRepository` (mover, renombrar, nombres duplicados).
- Firma de release para distribuir fuera de debug.
- Registrar o limpiar `.m4a` huérfanos (proceso muerto durante una grabación).
- Umbral de silencio configurable (hoy fijo en 1500).
- Ver la tabla completa de pendientes en `docs/DOCUMENTACION-TECNICA.md` §8.2.
