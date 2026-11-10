# Módulo 2: sanitización y compresión de imágenes

Implementación: `app/src/main/kotlin/com/tierruos/stickers/security/ImageSanitizerAndCompressor.kt`.
Usa Kotlin, corrutinas y APIs nativas de Android. Las cuatro dependencias directas
del Módulo 1 se mantienen sin cambios.

## API

Desde una corrutina:

```kotlin
val engine = ImageSanitizerAndCompressor(applicationContext)
val sticker = engine.createSticker(selectedUri)
val webpBytes: ByteArray = sticker.bytes
val sizeInKb: Double = sticker.sizeInKb
```

`createTrayIcon(selectedUri)` devuelve el mismo tipo de resultado, con lienzo de
96 × 96 píxeles. El resultado también expone `sizeBytes`, `width`, `height` y
`quality`. Los bytes son WebP estático en ambos métodos. La UI puede formatear
`sizeInKb` con la configuración regional del usuario y aplicar el estilo
`TextAppearance.Tierruos.FileSize` del Módulo 1.

Ambos métodos cambian a `Dispatchers.IO`. Un `Mutex` compartido serializa el
pipeline completo entre todas las instancias del proceso, incluidas las dos APIs.
La UI deberá gestionar su estado ocupado y bloquear su CTA durante la operación.

## Pipeline y límites

1. Se admiten `content://` y archivos de los directorios privados `filesDir` o
   `cacheDir`. Los archivos locales se verifican por ruta canónica. URI de red y
   archivos externos se rechazan con `SecurityException`.
2. El tamaño publicado por el proveedor y la longitud del descriptor se consultan
   para rechazar entradas grandes antes de leerlas. Ninguno reemplaza el conteo
   del stream. Un tamaño desconocido, nulo o falsificado sigue sujeto al límite.
3. El URI se abre una sola vez. Se copian bloques de 16 KiB a un archivo temporal
   aleatorio en `filesDir/image-imports`, con un máximo de **15.000.000 bytes**.
   Como máximo se inspecciona un byte adicional para detectar el exceso; ese byte
   no se escribe. La decodificación empieza después de completar esta copia.
4. Sobre esa misma instantánea privada se leen los primeros 12 bytes: JPEG
   `FF D8 FF`, la firma completa de 8 bytes de PNG o `RIFF` + `WEBP`. En WebP se
   comprueba también que la longitud RIFF coincida exactamente con la del archivo.
   Extensión, nombre y MIME del proveedor no intervienen en esta decisión.
5. `inJustDecodeBounds` valida dimensiones positivas, cada eje de hasta 8000 px y
   coherencia entre la firma y el formato reconocido por el decodificador. Un
   `inSampleSize` potencia de dos mantiene el eje mayor muestreado en hasta 1024
   px. El tamaño ARGB estimado se calcula con `Long` antes de decodificar.
6. Se solicita software ARGB_8888, sin escalado por densidad y con sRGB desde API
   26. Se verifican de nuevo las dimensiones, configuración y asignación reales
   del bitmap. El presupuesto del bitmap decodificado es 4 MiB.
7. Un `Canvas` dibuja y centra los píxeles conservando su proporción sobre un
   bitmap ARGB_8888 nuevo, transparente y de tamaño exacto. Esta única operación
   realiza tanto la sanitización destructiva como la normalización. El bitmap de
   entrada se recicla antes de empezar la compresión.
8. Se codifica con `WEBP_LOSSY` desde API 30 o `WEBP` en API 24–29. La calidad baja
   de 90 a 0 en pasos de 10. El buffer de salida tiene capacidad fija y nunca
   crece por encima del presupuesto. Una codificación que lo desborda se descarta.
9. Se retiran los chunks ICCP/EXIF/XMP que el propio codificador pueda generar,
   ajustando la longitud RIFF y los flags del encabezado VP8X. Después se verifica
   el RIFF de salida, sus dimensiones exactas y una única imagen. Solo quedan
   chunks de píxeles `VP8 `, `VP8L`, `VP8X` y `ALPH`; animaciones o chunks
   desconocidos causan error de compresión.
10. Los streams y descriptores se cierran y el temporal se elimina con `use`.
    Los bitmaps se reciclan con `finally`. Los temporales que deje una muerte del
    proceso se eliminan antes de la siguiente importación, bajo el mismo mutex.

| Resultado | Lienzo | Límite exclusivo |
| --- | --- | --- |
| Sticker | 512 × 512 px | 98.000 bytes |
| Ícono del pack | 96 × 96 px | 48.000 bytes |

Los límites usan MB/KB decimales, por lo que son conservadores también frente a
una interpretación binaria de 15 MB, 98 KB o 48 KB. Un resultado exactamente igual
al límite de salida se rechaza. La entrada admite exactamente 15.000.000 bytes.

## Errores y cancelación

| Tipo | Situación |
| --- | --- |
| `SecurityException` | Permiso denegado, esquema no admitido o ruta local fuera de los directorios privados |
| `InvalidImageFormatException` | Firma, cabecera, dimensiones inválidas o fallo de decodificación |
| `ImageTooLargeException` | Entrada superior a 15 MB, eje superior a 8000 px, presupuesto de memoria excedido, OOM capturable o salida que no cabe ni a calidad 0 |
| `ImageCompressionException` | Fallo del codificador, salida inválida o chunks ajenos a los píxeles |
| `IOException` | Fallo de lectura, almacenamiento o eliminación del temporal |
| `CancellationException` | Cancelación de la corrutina; se propaga |

Las tres excepciones de imagen derivan de `IOException`. Los diagnósticos son
internos; la UI debe mapear sus tipos a mensajes localizados en `strings.xml`.
La cancelación se comprueba entre lecturas y antes/después de decodificar, dibujar
y cada intento de compresión. Las llamadas bloqueantes del proveedor y del codec
nativo no se interrumpen a la fuerza.

El canal alfa de WebP se conserva sin pérdida. Una imagen con alfa de alta
entropía puede superar 98.000 bytes incluso a calidad 0. En ese caso se devuelve
el error tipado, conservando el tamaño y los píxeles solicitados; nunca se entrega
un resultado fuera del presupuesto.

## Alcance de la protección

El contenedor original, EXIF, XMP y datos anexados se eliminan al construir y
codificar un bitmap nuevo. Una firma válida no demuestra que el archivo completo
sea inocuo, y la recodificación sucede después de entrar en el codec nativo.
Este motor no aísla dicho codec ni garantiza protección contra una vulnerabilidad
de su implementación. Tampoco elimina mensajes esteganográficos que estén en
los propios píxeles. Capturar OOM y limitar asignaciones reduce el riesgo de
agotamiento; un fallo nativo del proceso no se convierte en una excepción Kotlin.

Se trabaja con los píxeles que entrega `BitmapFactory`: no se interpreta ni
reaplica orientación EXIF. La salida es una imagen estática; este módulo no crea
stickers animados. Los bytes originales nunca se entregan al consumidor.

## Pruebas

Verificación actualizada al integrar el Módulo 3, el 9 de noviembre de 2026:
debug, release y el APK instrumentado compilan correctamente. Los 19 casos del
motor pasan en Android 35, tanto en configuración de teléfono como de tablet,
dentro del conjunto de 35 pruebas. Los informes están en `docs/qa`.
`lintDebug` y `lintRelease` terminan sin errores; las advertencias del conjunto
actual y el tamaño de los APK están documentados en `MODULO_3.md`.

Hay un runner nativo basado en `android.app.Instrumentation`, sin JUnit ni AndroidX
Test. El proveedor privado de fixtures vive en el APK debug y expone únicamente
datos sintéticos al proceso de la app; está excluido del release. El runner y las
aserciones viven en el APK de pruebas. Las pruebas de los siguientes módulos se
incorporan al mismo runner.

Las pruebas cubren PNG/JPEG/WebP, transparencia y centrado, ícono 96 × 96, MIME y
nombre falsos, denegación de acceso, firmas corruptas, dimensiones nulas/excesivas,
PNG 8000 × 8000 generado por streaming, tamaño declarado excesivo, pipe de tamaño
desconocido, límite exacto de 15 MB, cambios del proveedor entre aperturas,
eliminación de EXIF/texto/datos anexados, WebP con datos posteriores, descenso de
calidad, alfa incompresible, cancelación y varias instancias concurrentes.
Son 19 casos instrumentados del motor; su ejecución en Android 35 está verificada.

Compilación y análisis, desde la raíz:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease :app:verifyReleaseApkSize
```

Con un emulador o dispositivo de pruebas Android conectado:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Para ejecutar manualmente sobre un dispositivo de pruebas:

```powershell
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.tierruos.stickers.test/com.tierruos.stickers.security.SanitizerInstrumentation
```

La validación real del codec se ejecutó en el emulador Android 35 del Módulo 3.
La rama de API 24–29 mantiene su guardia de versión y aún requiere verificación
en un dispositivo/emulador de esas versiones antes de distribuir una versión final.

La app aún no tiene pantallas que llamen al motor: R8 elimina sus clases no
alcanzables en release. El tamaño actual de los APK no mide el coste de una
integración futura del motor en la UI.

## Referencias

- [Decodificación de bitmaps con muestreo](https://developer.android.com/topic/performance/graphics/load-bitmap)
- [BitmapFactory.Options](https://developer.android.com/reference/android/graphics/BitmapFactory.Options)
- [Formatos WebP nativos y versiones de API](https://developer.android.com/reference/android/graphics/Bitmap.CompressFormat)
- [Contenedor WebP: chunks y alfa sin pérdida](https://developers.google.com/speed/webp/docs/riff_container)
- [Requisitos del proyecto oficial de stickers de WhatsApp](https://github.com/WhatsApp/stickers/blob/main/Android/README.md)
