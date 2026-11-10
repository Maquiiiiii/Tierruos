# Tierruos

<img src="docs/assets/tierruos-logo.png" width="128" alt="Logo de Tierruos" />

App nativa en Kotlin para crear stickers de WhatsApp rápido, sin anuncios, sin permisos raros y pesando menos de 4 MB.

[Descargar APK universal (v0.1.4)](https://github.com/Maquiiiiii/Tierruos/releases/download/v0.1.4/Tierruos-universal.apk) · [Todas las versiones y SHA-256](https://github.com/Maquiiiiii/Tierruos/releases)

- **Versión actual:** `0.1.4` (`versionCode = 5`)
- **Tamaño del APK firmado:** `1.271.731 bytes` (~1,27 MB)
- **Requisitos:** Android 7.0 (API 24) o superior

## Qué hace

- Importa imágenes desde la galería (Photo Picker del sistema) o directamente desde el portapapeles.
- Permite recortar a mano alzada (lazo), borrar fondo, mover/rotar/escalar, agregar borde blanco y texto, con deshacer por pasos.
- Normaliza cada sticker a WebP de `512x512 px` con margen interno de `16 px`, fondo transparente y menos de `98 KB`.
- Genera automáticamente el ícono del pack (`tray.webp`) a `96x96 px` y menos de `48 KB` a partir del primer sticker, asignando emojis por defecto (`☕`) para pasar la validación de WhatsApp y WhatsApp Business.
- Guarda todo en almacenamiento interno privado (`filesDir/stickers/` + SQLite) y lo expone a WhatsApp mediante un `ContentProvider` de solo lectura. No pide permiso de internet ni de lectura de almacenamiento global.

## Compilación desde código fuente

Necesitas JDK 17 y el SDK de Android 35 (`platforms;android-35` y `build-tools;35.0.0`).

En PowerShell:

```powershell
.\gradlew.bat :app:assembleRelease :app:verifyReleaseApkSize
```

En Linux o macOS:

```sh
./gradlew :app:assembleRelease :app:verifyReleaseApkSize
```

Los APK sin firmar se generan en `app/build/outputs/apk/release/`. El APK publicado en GitHub Releases se alinea con `zipalign` y se firma con esquemas v2/v3 usando la clave local del proyecto.

## Estructura del proyecto

```text
Tierruos/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── kotlin/com/tierruos/stickers/
│       │   │   ├── data/        # SQLiteOpenHelper, modelos y repositorio de archivos
│       │   │   ├── export/      # Intent de envío a WhatsApp y WhatsApp Business
│       │   │   ├── graphics/    # Contorno circular sobre canal alfa
│       │   │   ├── provider/    # ContentProvider IPC para WhatsApp
│       │   │   ├── security/    # Decodificación acotada, escalado y compresión WebP
│       │   │   └── ui/          # MainActivity, StickerEditorActivity y StickerCanvasView
│       │   └── res/
│       ├── debug/               # Actividades de prueba y fixtures
│       └── androidTest/         # Pruebas instrumentadas en dispositivo/emulador
└── docs/                        # Notas técnicas y registros de pruebas
```

## Dependencias

Solo usa cuatro librerías directas: `androidx.core:core-ktx`, `androidx.appcompat:appcompat`, `com.google.android.material:material` y `kotlinx-coroutines-android`. Sin Compose, sin Room, sin librerías externas de edición de imagen ni SDKs de terceros.
