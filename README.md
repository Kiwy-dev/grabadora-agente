# Grabadora Agente

App Android que graba audios, los organiza en carpetas y puede **activarse con una palabra clave**
("oye grabadora") sin estar escuchando las 24 horas. Funciona **sin internet**.

Está hecha en Kotlin con vistas XML clásicas, siguiendo *El gran libro de Android*
(Tomás Gironés y Tirado Gil, 8.ª ed.). Los comentarios del código dicen de qué capítulo sale cada técnica.

> **Estado:** la 0.3 se probó en un teléfono real. La 0.4 (revisión de código del 22-11-2026) compila y pasa lint; falta probarla en teléfono.

---

## Contenido del repositorio

```
GrabadoraAgente/
├─ app/                                ← código fuente
├─ scripts/descargar-modelo.ps1        ← descarga el modelo de voz (solo para compilar)
├─ docs/DOCUMENTACION-TECNICA.md       ← arquitectura, flujo del agente, datos y revisión de código
├─ README.md                           ← este archivo
└─ AGENTS.md                           ← guía para asistentes de IA que trabajen en el código
```

---

## 1. Instalar la app (sin compilar)

Requisitos: un teléfono con **Android 8.0 o superior**.

**Opción A: copiando el APK al teléfono**
1. Descarga `GrabadoraAgente-0.4-debug.apk` de la sección **Releases** del repositorio y pásalo al teléfono (por cable, Drive, WhatsApp, etc.).
2. Ábrelo desde el gestor de archivos.
3. Si Android lo bloquea, permite **"Instalar apps desconocidas"** para esa app y vuelve a abrirlo.

**Opción B: con adb desde la PC**
1. En el teléfono activa **Opciones de desarrollador → Depuración USB** y conéctalo por cable.
2. Ejecuta:
   ```
   adb install -r GrabadoraAgente-0.4-debug.apk
   ```

> Es una versión *debug* firmada con una clave de desarrollo. Sirve para probar y compartir,
> no para publicarla en Google Play.

---

## 2. Cómo se usa

### Grabar a mano
- Pulsa **Grabar** (abajo a la derecha). Pulsa **Detener** para terminar.
- El audio se guarda en la carpeta que esté seleccionada arriba.
- La primera vez, la app pide permiso de **micrófono**.

### Carpetas
- Las carpetas se ven como botones arriba de la lista. Toca una para ver sus audios.
- Menú **⋮** de la barra → **Nueva carpeta** o **Borrar carpeta actual**. Solo se pueden borrar carpetas
  vacías, y nunca la carpeta *General*.

### Acciones de cada audio
- **Toca un audio** para reproducirlo y vuelve a tocarlo para pararlo.
- Botón **⋮** de cada audio:
  - **Compartir:** envíalo por WhatsApp, correo, Drive, etc.
  - **Mover a carpeta:** a una carpeta existente o a una nueva.
  - **Renombrar.**
  - **Borrar:** pide confirmación y no se puede deshacer.

### Palabra clave (modo agente)
1. Activa el interruptor **"Escuchar palabra clave"**. También puedes usar el mosaico **"Grabadora"** de los
   ajustes rápidos: desliza la barra de notificaciones, toca editar (✎) y arrastra el mosaico.
2. Di **"oye grabadora"**. La app empieza a grabar sola.
3. Deja de grabar cuando hay unos segundos de silencio, o al llegar a la duración máxima.
4. Guarda el audio en la carpeta **"Por voz"** y vuelve a escuchar.
5. Para apagarla, usa el interruptor, el mosaico o el botón **Detener** de la notificación.

Mientras escucha, se ve una notificación fija. Android la exige para cualquier app que use el micrófono en segundo plano.

### Para que no esté activa 24/7
Todo se configura en **⋮ → Ajustes**:

| Ajuste | Valor por defecto |
|---|---|
| Apagar tras X minutos sin oír la palabra clave | 10 min |
| Pausar con la pantalla apagada (se reanuda al encenderla) | Sí |
| Apagar con batería baja | Sí |
| Solo mientras se carga (se apaga al desconectar) | No |
| Detener la grabación tras un silencio de | 3 s |
| Duración máxima de cada grabación | 1 min |
| Palabra clave | "oye grabadora" |
| Carpeta de las grabaciones por voz | "Por voz" |

Además, si Android cierra el servicio, **no se reinicia solo**.

**Consejo para la palabra clave:** usa 2 o 3 palabras comunes en español. Las palabras inventadas o en
inglés no las reconoce el modelo.

### Dónde quedan los audios
`Almacenamiento interno/Android/data/com.grabadora.agente/files/Music/Grabaciones/<carpeta>/`

⚠️ **Si desinstalas la app, se borran los audios.** Comparte o copia los que quieras conservar.

---

## 3. Compilar desde el código

Requisitos:
- **JDK 17 o 21.** No sirve un JDK más nuevo, porque Gradle 8.10 no lo soporta.
- **Android SDK** con `platforms;android-35` y `build-tools;35.0.0`. Lo más fácil es instalar **Android Studio**, que trae las dos cosas.

Pasos:
1. Descarga el modelo de voz en español (~39 MB, una sola vez). El repositorio no lo incluye, para que pese menos:
   ```
   powershell -ExecutionPolicy Bypass -File scripts\descargar-modelo.ps1
   ```
   El modelo queda en `app/src/main/assets/model-es/`.
2. Abre la carpeta en Android Studio y pulsa **Run**. O bien, desde la terminal:
   ```
   gradlew assembleDebug
   ```
   Si Gradle no encuentra el SDK, crea un archivo `local.properties` con la ruta de tu SDK:
   `sdk.dir=C:/Users/TU_USUARIO/AppData/Local/Android/Sdk`
3. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

Si compilas sin el modelo, la app funciona, pero al activar la escucha avisa que falta el modelo y se apaga.

---

## 4. Qué parte del libro usa cada función

| Función | Archivo | Capítulo |
|---|---|---|
| Grabar | `record/Recorder.kt` | 6: MediaRecorder (ejercicio "Grabadora") |
| Reproducir | `ui/MainActivity.kt` | 6: MediaPlayer y ciclo de vida |
| Lista y carpetas | `ui/AudioAdapter.kt`, `layout/activity_main.xml` | 2: Layouts, 3: RecyclerView |
| Borrar, mover y renombrar | `data/AudioRepository.kt` | 9: Ficheros externos y SQLite |
| Compartir | `MainActivity.compartir()` | 3: Intent `ACTION_SEND` (+ FileProvider) |
| Permisos | `MainActivity.onRequestPermissionsResult()` | 7: Permisos en tiempo de ejecución |
| Escucha por palabra clave | `agent/KeywordService.kt` | 8: Servicio en primer plano y notificación |
| Pausa y apagado automáticos | `KeywordService.receptor` | 8: BroadcastReceiver (pantalla, batería, cargador) |
| Ajustes | `ui/SettingsActivity.kt`, `xml/preferencias.xml` | 3 y 9: Preferencias |
| Repositorio único | `AudioApp.kt` | 3: Application / Singleton |

**Fuera del libro:** el reconocimiento de la palabra clave usa [Vosk](https://alphacephei.com/vosk/),
que funciona sin internet y está limitado a una gramática cerrada (solo la frase clave).

---

## 5. Problemas comunes

| Problema | Solución |
|---|---|
| "Falta el modelo de voz" | Compilaste sin el modelo: ejecuta `scripts\descargar-modelo.ps1` y recompila. El APK de Releases ya lo trae. |
| No reacciona a la palabra clave | Habla claro y cerca. Prueba con otra frase común en Ajustes. Revisa que el estado diga "Escuchando". |
| Se apaga sola | Es lo esperado: inactividad, batería baja o cargador desconectado. El motivo aparece debajo del interruptor. |
| "Apaga la escucha antes de grabar a mano" | El micrófono solo lo puede usar una cosa a la vez. Apaga el interruptor y vuelve a grabar. |
| El mosaico no arranca la escucha | Abre la app una vez y concede el permiso de micrófono. Con el teléfono bloqueado, el mosaico pide desbloquearlo primero. |
| "Android no permitió iniciar la escucha" | Android 12+ no deja abrir el micrófono con la app en segundo plano. Abre la app y usa el interruptor. |

Más detalle técnico (arquitectura, datos, decisiones y pendientes) en [`docs/DOCUMENTACION-TECNICA.md`](docs/DOCUMENTACION-TECNICA.md).
