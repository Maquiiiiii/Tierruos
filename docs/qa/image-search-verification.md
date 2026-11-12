# Recepción compartida y búsqueda de imágenes — 2026-11-12

Versión del código para el commit: **0.1.9** (`versionCode = 10`). La recompilación
con estos metadatos pasó los mismos objetivos de Gradle y mantuvo 1.491.779 bytes
por APK release; véase image-search-v019-build.txt. La suite instrumentada descrita
abajo se ejecutó antes del incremento de versión, con el mismo código funcional.
Crear este commit no publica un APK nuevo en GitHub Releases.

Implementación nativa Kotlin/XML, sin cambios en las cuatro dependencias ni en los permisos.
El editor sigue privado; MainActivity recibe SEND image/*, extrae una URI content local
con IntentCompat y reenvía únicamente la imagen y un permiso temporal de lectura.
El sanitizador, StickerCanvasView, data/ y el proveedor de WhatsApp mantienen su código original.

La herramienta «Buscar en Google Lens» exporta el lienzo mediante exportFinalBitmap().
Solo la copia exportada se aplana sobre blanco para generar un PNG en cacheDir/image-search/.
El FileProvider existente expone exclusivamente esa nueva ruta. Se intenta Lens, Google y
finalmente un chooser con los componentes propios excluidos. Se controlan aplicaciones
ausentes o sin acceso. Al regresar se revoca el permiso y se elimina el temporal; los huérfanos
de más de 24 horas se limpian en aperturas posteriores.

La comprobación del portapapeles requiere una búsqueda pendiente y retorno de foco.
Ignora la URI anterior, avisa de enlaces web y valida la imagen con el sanitizador antes
de ofrecer «Reemplazar / Conservar». La aceptación utiliza los bytes ya validados, sin
reabrir la URI. Conservar mantiene las ediciones y deshacer; reemplazar reinicia ambos.
El estado de búsqueda y la imagen propuesta se conservan mediante estado de Activity.

## Compilación

Se usó JDK 21.0.8 instalado y Android SDK 35. Gradle instaló plataforma/build-tools 35
con las licencias ya aceptadas. Los sockets locales de Java requirieron un directorio
temporal corto, sin cambios persistentes de configuración:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/openjdk/jdk-21.0.8'
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:/tmp -Djava.io.tmpdir=C:/tmp'
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:verifyReleaseApkSize :app:assembleDebugAndroidTest --no-daemon --console=plain
```

Resultado: BUILD SUCCESSFUL. Cada APK release (arm64-v8a, armeabi-v7a, x86_64 y universal)
pesa **1.491.779 bytes**, por debajo de 4.000.000. Los release son los artefactos sin firmar
que genera el proyecto. El límite se aplica a release; debug incluye recursos de prueba.

Sin advertencias nuevas. La compilación original reproduce las deprecaciones de scaledDensity,
computeBounds y SOFT_INPUT_ADJUST_RESIZE; permanece la opción experimental existente de R8.
Véanse image-search-build.txt, image-search-initial-build.txt y android36-baseline-build.txt.

## Instrumentación

Emulador conectado: Android 36, x86_64. Se instalaron APK debug y androidTest y se ejecutó:

```powershell
adb shell am instrument -w -r com.tierruos.stickers.test/com.tierruos.stickers.security.SanitizerInstrumentation
```

**112/117 pruebas pasan. Las 11 nuevas pasan.** Incluyen recepción real mediante MainActivity,
extras inválidos, conservación del editor privado y permisos, PNG blanco y FileProvider,
orden de intentos, ausencia de Google y de receptores, exclusión propia, URI anterior/enlace web,
y confirmación real de conservar/reemplazar con validación y protección del progreso.

Se compiló y ejecutó una copia aislada de Downloads/Tierruos-main.zip como control:
**101/106 pasan**, con exactamente los mismos cinco fallos de las pruebas originales:

- dataSavesPrivateWebpAndExactByteMetric: comparación de tray anterior a la sincronización.
- ipcRegisteredProviderServesOwnResolver: expectativa de MIME nulo no satisfecha en API 36.
- ipcAssetsUseReadOnlyDescriptorsAndExactBytes: tamaño esperado del tray de fixture.
- presentationEditorPastesClipboardThroughSecurityPipeline: clip de URI no conservado por el entorno.
- createPackAdaptiveActionsLayoutNeverClipsButtons: inflación del diálogo desde contexto de aplicación.

No se alteraron esas pruebas ni sus módulos para ocultar los fallos. Registros completos:
android36-image-search-tests.txt y android36-baseline-tests.txt.

La sincronización del portapapeles del emulador pierde clips de URI sin texto. Las nuevas
pruebas de retorno usan la representación textual de una URI local, que ClipboardImages
ya admite; la extracción de ClipData con URI también tiene comprobación directa.
El runner permite -e testPrefix search o -e testPrefix shared para verificaciones acotadas.

Límites: ejecución instrumentada en API 36; no se ejecutó en API 24 ni 35. Los fallbacks
se prueban con lanzamiento inyectado para simular paquetes ausentes. No se verificó una
búsqueda HD completa dentro de Google ni el comportamiento de sus versiones de Lens.
