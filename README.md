# Tierruos — Módulos 1–6

[Descargar APK universal para Android](https://github.com/Maquiiiiii/Tierruos/releases/latest/download/Tierruos-universal.apk)
· [Versiones y comprobación SHA-256](https://github.com/Maquiiiiii/Tierruos/releases)

Requiere Android 7.0 o posterior. La descarga corresponde a la versión release
firmada; la clave privada de firma no se incluye en este repositorio.

Base Android nativa de `com.tierruos.stickers`. El Módulo 1 configura el proyecto,
la compilación, los recursos de tema y el manifest. Desde el Módulo 6 el APK
incluye la biblioteca MainActivity y el editor de stickers de producción;
los hosts de catálogo y fixtures siguen siendo exclusivos de debug.

El Módulo 2 añade el motor de sanitización y compresión. Su API, límites,
excepciones y pruebas están en [docs/MODULO_2.md](docs/MODULO_2.md).

El Módulo 3 añade el lienzo con transformación, lazo, borrador, borde, texto,
deshacer y exportación de bitmap. Su integración está en [docs/MODULO_3.md](docs/MODULO_3.md).
En Android 35 se ejecutaron **35 pruebas sin fallos** tanto en tamaño de teléfono
como de tablet. Los informes y capturas nativas están en [docs/qa](docs/qa).
En la verificación del Módulo 3, los cuatro APK release sin firma pesaban
702.245 bytes cada uno.

El Módulo 4 incorpora modelos, CRUD con `SQLiteOpenHelper` y gestión recuperable
de WebP en `filesDir/stickers/`. La API y sus contratos están en
[docs/MODULO_4.md](docs/MODULO_4.md). La regresión completa en Android 35 de teléfono
supera **57 pruebas sin fallos**, incluidas 22 de datos y archivos. Debug, release,
R8 y lint se verificaron; lint mantiene 0 errores y advertencias visibles.

El Módulo 5 completa los estilos XML y nueve íconos, publica el ContentProvider
de solo lectura e incorpora el motor de exportación a WhatsApp y Business.
El contrato, el nodo de manifest y la integración están en
[docs/MODULO_5.md](docs/MODULO_5.md). La previsualización de estilos es solo debug.
Verificado el 10 de noviembre de 2026: **78 pruebas sin fallos** en Android 35
de teléfono y tablet (esta última con fuente al 130 %). Lint no presenta errores;
mantiene 21/38 advertencias en debug/release. Los cuatro APK release sin firma
pesan **721.297 bytes** cada uno. La confirmación dentro de WhatsApp real queda
pendiente; los Intents se prueban con un recorder exclusivo de debug.

El Módulo 6 conecta biblioteca, miniaturas reales, editor, portapapeles y Photo
Picker mediante ViewBinding. Incluye creación de packs, edición, borrado y
confirmación de resultados de exportación. Uso, seguridad, límites geométricos
del lienzo cuadrado y pruebas están en [docs/MODULO_6.md](docs/MODULO_6.md).
Verificación actual: **99 pruebas sin fallos** en Android 35 de teléfono, teléfono
con fuente al 130 % y tablet. Debug/release y lint correctos, sin errores de lint
(25/24 advertencias visibles). Cada APK release sin firma pesa **1.138.440 bytes**.

## Validación inicial del Módulo 1

Verificado el 9 de noviembre de 2026 con JDK 17 y SDK 35:

- `:app:assembleDebug`: correcto.
- `:app:lintRelease`: correcto, 0 errores y 21 advertencias.
- `:app:verifyReleaseApkSize`: correcto, incluida la compilación release con R8.
- Los cuatro APK de release sin firma pesan 696.549 bytes cada uno.
- El manifest combinado conserva ambos paquetes de WhatsApp y no incorpora
  permisos de almacenamiento, red ni consulta global de aplicaciones.

Las advertencias de lint corresponden a versiones posteriores disponibles,
tokens todavía no usados por pantallas y el icono de aplicación aún pendiente.
Se conservan las advertencias visibles; no se deshabilitan comprobaciones ni se
incluye una baseline para ocultarlas. El objetivo SDK 35 es el solicitado.

## Requisitos y compilación

- JDK 17.
- Android SDK Platform 35 y Android SDK Build Tools 35.0.0.
- Acceso a Google Maven, Maven Central y los repositorios oficiales de Gradle.
- Gradle 8.13 mediante el Wrapper incluido; AGP 8.13.2 y Kotlin 2.3.10.

Abrir esta carpeta en Android Studio y seleccionar JDK 17 como Gradle JDK.
El IDE genera `local.properties` con la ruta del SDK. Alternativamente, definir
`ANDROID_HOME` y `JAVA_HOME` para el terminal.

En PowerShell, desde la raíz:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintRelease :app:verifyReleaseApkSize
```

En Linux/macOS, habilitar la ejecución del Wrapper con `chmod +x gradlew` y ejecutar:

```sh
./gradlew :app:assembleDebug :app:lintRelease :app:verifyReleaseApkSize
```

Los cuatro APK de producción se generan en `app/build/outputs/apk/release/`:

- `app-arm64-v8a-release-unsigned.apk`
- `app-armeabi-v7a-release-unsigned.apk`
- `app-x86_64-release-unsigned.apk`
- `app-universal-release-unsigned.apk`

La compilación local de Gradle genera release sin firma; la distribución publicada
en GitHub Releases está firmada con una clave privada de Tierruos. No se incluye
ninguna clave ni se usa la firma de debug para una compilación de producción.
La tarea `verifyReleaseApkSize` exige que cada archivo pese menos de 4.000.000 bytes;
fallará si falta algún APK o se supera ese presupuesto. Sin bibliotecas `.so`, los
splits no aportan una reducción sustancial de peso. El tamaño de esta base no
predice el tamaño de la app terminada.

## Estructura

```text
Tierruos/
├── .gitattributes
├── .gitignore
├── README.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        └── main/
            ├── AndroidManifest.xml
            ├── kotlin/com/tierruos/stickers/
            │   ├── data/
            │   │   ├── StorageContract.kt
            │   │   ├── TierruosStorage.kt
            │   │   ├── db/
            │   │   │   └── TierruosDatabaseHelper.kt
            │   │   ├── model/
            │   │   │   ├── Sticker.kt
            │   │   │   └── StickerPack.kt
            │   │   └── repository/
            │   │       ├── StickerFileRepository.kt
            │   │       └── WebpAssetValidation.kt
            │   ├── export/
            │   │   └── WhatsAppExporter.kt
            │   ├── graphics/
            │   │   └── StickerOutline.kt
            │   ├── provider/
            │   │   ├── StickerContentProvider.kt
            │   │   ├── StickerProviderContract.kt
            │   │   └── StickerProviderStore.kt
            │   ├── security/
            │   │   └── ImageSanitizerAndCompressor.kt
            │   ├── ui/
            │   │   ├── MainActivity.kt
            │   │   ├── StickerPackAdapter.kt
            │   │   ├── StickerEditorActivity.kt
            │   │   ├── ClipboardImages.kt
            │   │   ├── PresentationSupport.kt
            │   │   ├── SquareCanvasHost.kt
            │   │   └── StickerCanvasView.kt
            │   └── util/
            └── res/
                ├── drawable/
                ├── layout/
                │   ├── activity_main.xml
                │   ├── item_sticker_pack.xml
                │   ├── item_sticker_thumbnail.xml
                │   ├── activity_sticker_editor.xml
                │   ├── dialog_pack_details.xml
                │   ├── dialog_sticker_text.xml
                │   ├── view_busy_overlay.xml
                │   └── view_sticker_canvas.xml
                ├── values/
                │   ├── colors.xml
                │   ├── dimens.xml
                │   ├── strings.xml
                │   └── themes.xml
                └── xml/
                    ├── backup_rules.xml
                    └── data_extraction_rules.xml
```

Los directorios todavía vacíos están reservados con `.gitkeep`.
El motor y sus excepciones viven en `security/ImageSanitizerAndCompressor.kt`;
las pruebas están en `app/src/androidTest/`. El host de previsualización y el
proveedor privado de fixtures viven en `app/src/debug/` y no se incorporan a
release. Los directorios
`build/`, `.gradle/`, `.kotlin/`, `.tools/` y `local.properties` son locales y se
excluyen del control de versiones.

## Dependencias

Solo hay cuatro declaraciones `implementation`: Core KTX 1.15.0, AppCompat 1.7.0,
Material Components 1.12.0 y Coroutines Android 1.10.2. Sus dependencias transitivas
y la biblioteca estándar de Kotlin forman parte de la resolución normal de
Gradle; no se añaden Compose, Glide, OpenCV, Lottie, Room ni un cargador de imágenes.
No se excluyen dependencias transitivas necesarias para ahorrar peso.

AppCompat 1.7.0 aporta Activity 1.7.0 o superior transitivamente; la resolución
de este proyecto selecciona Activity 1.8.0, que proporciona
`ActivityResultContracts.PickVisualMedia`. No es necesario agregar Activity como
quinta dependencia directa. En Android 24–32 no se debe asumir que el selector
fotográfico está disponible: el contrato usa alternativas del sistema, con
`ACTION_OPEN_DOCUMENT` como último recurso. Ninguna requiere acceso global al
almacenamiento. Ambas pantallas del Módulo 6 registran ese contrato con ImageOnly.

## Optimización y contratos de R8

Release usa `proguard-android-optimize.txt`, R8 full mode y el optimizador integrado
de recursos disponible en AGP 8.13. No se desactiva la ofuscación, no se silencian
advertencias globalmente y no se conservan paquetes completos de dependencias.
R8 no necesita parámetros heredados de ProGuard como `-optimizationpasses`.

Los modelos viven en `com.tierruos.stickers.data.model`. Se preservan
los nombres de clases y miembros que sobrevivan, permitiendo su optimización y
la eliminación de elementos no usados. SQLiteOpenHelper usa mapeo explícito;
esta regla no pretende habilitar serialización por reflexión. Un uso posterior de
reflexión requerirá reglas específicas para los miembros consultados.

El punto de entrada para WhatsApp es
`com.tierruos.stickers.provider.StickerContentProvider`. Su regla condicional
conserva la clase y el constructor público sin argumentos. Las
reglas de Android conservan los callbacks del framework. Sus helpers no reciben
un `keep` general. El provider está registrado con autoridad
`${applicationId}.stickercontentprovider`, permiso de lectura de WhatsApp y
validación del paquete llamador. Un cambio de nombre de paquete o clase debe
actualizar estas reglas y la allowlist del contrato.

Guardar `app/build/outputs/mapping/release/mapping.txt` con cada distribución para
poder reconstruir trazas ofuscadas. No subir ese archivo ni los APK sin firma como
artefactos de una versión publicada.

## Manifest y seguridad

No se solicita ningún permiso de almacenamiento ni de red. `<queries>` permite
consultar únicamente `com.whatsapp` y `com.whatsapp.w4b`; no concede acceso a sus
datos. No se usa `QUERY_ALL_PACKAGES`.

AndroidX incorpora en el manifest combinado un permiso interno
`com.tierruos.stickers.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` de protección
`signature`, además de sus inicializadores habituales. No es un permiso peligroso
ni da acceso al almacenamiento del usuario.

Las copias de seguridad y la transferencia de los dominios de datos privados se
excluyen mediante reglas para Android 24–30 y Android 31+. El tráfico HTTP sin
cifrar está deshabilitado como configuración preventiva de la aplicación.

El motor del Módulo 2 recibe URI y aplica límites de bytes, firma, dimensiones,
muestreo y recodificación destructiva sin EXIF/XMP. El Photo Picker y el pegado
del Módulo 6 pasan por ese motor antes de instalar píxeles en el editor.

## Sistema visual base

Los colores, tamaños, espaciados y radios se centralizan en `colors.xml`,
`dimens.xml` y `themes.xml`; los textos traducibles irán en `strings.xml`.
El tema fija el fondo `#0E0E11`, superficies `#18181C`, acento `#25D366`, texto
principal `#F4F4F6` y secundario `#A1A1AA`. El texto sobre el acento es oscuro.
La tarjeta tiene radio 20 dp, padding 8 dp y contenido interno de radio 12 dp.
Los títulos usan salto balanceado sin separación silábica; los contadores tienen
cifras tabulares. Monospace queda reservado al tamaño del WebP.

El lienzo del Módulo 3 se verificó visualmente en Android 35 con configuraciones
de teléfono y tablet, tema oscuro y fuente al 130 %. La vista previa de debug se
puede abrir en un dispositivo de pruebas con:

```powershell
adb shell am start -n com.tierruos.stickers/com.tierruos.stickers.ui.CanvasPreviewActivity
```

## Referencias oficiales

- [AGP 8.13: Gradle 8.13 y JDK 17](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Compatibilidad del plugin de Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Optimización con R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
- [APK por arquitectura](https://developer.android.com/build/configure-apk-splits)
- [Photo Picker y su fallback](https://developer.android.com/training/data-storage/shared/photo-picker)
- [AppCompat 1.7.0 y Activity 1.7.0](https://developer.android.com/jetpack/androidx/releases/appcompat#1.7.0)
- [Checksums oficiales de Gradle](https://gradle.org/release-checksums/)
