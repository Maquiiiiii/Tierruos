# Módulo 3: lienzo nativo de stickers

`StickerCanvasView` hereda directamente de `android.view.View`. Las cuatro
dependencias de producción se mantienen; usa `Bitmap`, `Canvas`, `Path`, `Matrix`,
`ScaleGestureDetector`, animadores nativos y las corrutinas ya disponibles.

## Archivos

- `app/src/main/kotlin/com/tierruos/stickers/ui/StickerCanvasView.kt`: vista y estado de edición.
- `app/src/main/kotlin/com/tierruos/stickers/graphics/StickerOutline.kt`: dilatación circular de alfa.
- `app/src/main/res/layout/view_sticker_canvas.xml`: contenedor listo para ViewBinding.
- `app/src/main/res/values/`: colores, medidas, textos, acciones accesibles y tiempos.
- `app/src/debug/`: host de previsualización y fixtures, excluidos del release.
- `app/src/androidTest/`: pruebas nativas del lienzo y del motor de seguridad.

## Integración

La entrada debe ser un bitmap de software previamente sanitizado por el Módulo 2,
con dimensiones positivas de hasta 1024 px por eje. La vista hace su propia copia
ARGB_8888 de 512 × 512. El bitmap del llamador conserva su propiedad y puede
liberarse después de `setImageBitmap`.

El layout genera `ViewStickerCanvasBinding` y su propiedad `stickerCanvas`.
En el hilo principal:

```kotlin
val editor = binding.stickerCanvas
editor.setImageBitmap(sanitizedBitmap)
editor.setTextLayer("Tierruos")
editor.mode = StickerCanvasView.MODE_TRANSFORM
```

Todos los métodos y propiedades de edición pertenecen al hilo principal.

| API | Comportamiento |
| --- | --- |
| `setImageBitmap(bitmap)` | Carga una copia y reinicia el historial; cancela operaciones anteriores |
| `mode = MODE_TRANSFORM` | Arrastrar imagen o texto; dos dedos para ampliar y girar imagen |
| `mode = MODE_LASSO_CROP` | Dibujar silueta y conservar su interior al levantar el dedo |
| `mode = MODE_ERASER` | Borrar sobre una copia privada de píxeles |
| `eraserWidthPx = 32f` | Diámetro de borrador, de 4 a 128 unidades del documento |
| `applyOutline(8f)` | Generar borde blanco en segundo plano; rango 0–32 |
| `applyOutline(0f)` | Retirar el borde |
| `setTextLayer(text, sizePx, centerX, centerY)` | Crear, sustituir o posicionar una capa de texto |
| `setTextLayer("")` | Retirar el texto |
| `translateImageBy(dx, dy)` | Trasladar mediante controles externos o teclado |
| `scaleImageBy(factor)` | Escalar entre 0,2× y 4× |
| `rotateImageBy(degrees)` | Girar alrededor del centro del documento |
| `resetImageTransform()` | Restablecer posición, escala y giro de la imagen |
| `undo()` | Deshacer una acción; devuelve si había una disponible |
| `canUndo`, `hasImage`, `isBusy` | Estado para los controles del contenedor |
| `exportFinalBitmap()` | Bitmap nuevo e independiente, ARGB_8888, transparente, 512 × 512 |
| `clearEditor()` | Cancelar trabajo, liberar referencias al documento y reiniciar la vista |

`onBusyChanged` permite bloquear el CTA y las herramientas del contenedor.
`onEditChanged` recibe el estado de deshacer; `onError` y `lastOperationError`
exponen fallos. La propia vista muestra carga y bloquea sus gestos durante trabajo
asíncrono. `exportFinalBitmap` y los cambios programáticos requieren una imagen
cargada y ninguna operación/gesto en curso; en otro caso lanzan
`IllegalStateException`.

El bitmap exportado pertenece al llamador. Se puede comprimir o guardar fuera
del hilo principal y liberar después de su último uso. La vista no escribe
archivos ni decide la persistencia del sticker.

## Coordenadas, capas y herramientas

El documento usa siempre 512 × 512 unidades, independientemente de la densidad,
orientación o tamaño medido de la vista. Se renderiza la misma composición tanto
en pantalla como al exportar. El borrador, las posiciones de texto y el borde
reciben unidades de ese documento.

La matriz de imagen transforma conjuntamente la imagen y su borde. El texto
permanece como capa independiente. El primer dedo sobre el texto lo arrastra;
un segundo dedo cambia a transformación de imagen. Los cambios de puntero
recalculan el foco para evitar saltos al levantar uno de los dedos.

El lazo se suaviza con curvas cuadráticas y se cierra al levantar el dedo. Su
relleno usa `EVEN_ODD`. El bitmap transformado se compone sobre una imagen
transparente completa antes de aplicarlo con `SRC_IN`; así no quedan restos
blancos de la máscara fuera de los límites de una imagen reducida o girada.
Los trazos se limitan a 4096 puntos; un exceso cancela el gesto y muestra cómo
recuperarse.

El borrador usa `PorterDuffXfermode(CLEAR)` sobre un bitmap privado de software,
con extremos redondos y muestras históricas del evento táctil. No borra el fondo
de la vista. `ACTION_CANCEL` descarta su copia provisional. Si no cambió ningún
píxel, el gesto no añade una entrada al historial.

El borde usa una transformación de distancia euclídea en dos pasadas, O(ancho ×
alto), sobre alfa con umbral 128. Produce dilatación circular con un píxel de
suavizado, sin desenfoque ni sombra. La capa se calcula con `Dispatchers.Default`,
y se regenera después de recortar o borrar. No se destruye la imagen para aplicar
o retirar el efecto. Las partes que queden fuera del documento se recortan al
exportar.

El texto admite hasta 200 unidades UTF-16, tamaño 12–72 y salto balanceado sin
separación silábica. Usa sans en negrita, relleno claro y contorno oscuro, con
saltos de línea nativos y sin cursiva. Los textos que exceden el alto disponible
se rechazan; no se sustituyen por puntos suspensivos. Su tamaño de documento es
estable al cambiar la escala de fuente del sistema; los mensajes de la interfaz
sí usan `sp`.

## Diseño y feedback

Modo de la superficie: **Operate**, edición directa de píxeles. Mantiene el
sistema oscuro del proyecto y el único acento verde.

El layout aplica el margen exterior de 16 dp mediante el padre, que es quien
gestiona márgenes en Android. La tarjeta tiene radio 20 dp y padding 8 dp, y el
lienzo interior radio 12 dp. Se desactiva el padding adicional por esquinas de
CardView para conservar la relación 20 − 8 = 12.

Con ancho exacto y alto libre, la vista se mide cuadrada y ocupa el ancho máximo.
Si el padre fuerza dos dimensiones rectangulares, se respetan sus restricciones
y se centra un área cuadrada: el documento nunca se deforma. El host debe dejar
alto suficiente para el ancho que solicita.

La cuadrícula repite `#18181C` y `#222228` en celdas de 8 dp. Los radios de la
interfaz, cuadrícula, guías, indicador del borrador, selección, mensajes de carga
y transición de deshacer se excluyen de la exportación.

Al completar un lazo o borde se solicita háptica `CONFIRM` desde API 30, con
`VIRTUAL_KEY` en API 24–29, respetando las preferencias del sistema y sin añadir
permisos. El pulso usa `ViewPropertyAnimator`, escala 0,97 → 1, duración 180 ms y
`OvershootInterpolator`. Deshacer usa una imagen transitoria que se desvanece en
180 ms; el bitmap exportado refleja de inmediato el documento restaurado.
Las animaciones se omiten si el sistema las desactiva.

Hay acciones accesibles para deshacer, girar y ampliar/reducir, descripciones de
cada herramienta y áreas de texto arrastrables de al menos 48 dp. Flechas del
teclado trasladan la imagen, +/− cambian escala y Ctrl+Z deshace.

## Historial y ciclo de vida

Cada gesto completo constituye una acción. Las matrices y capas de texto se
guardan como estados; los bitmaps inmutables se comparten entre estados hasta
una edición de píxeles. El historial conserva hasta 8 entradas y un máximo de
16 MiB de bitmaps distintos entre documento e historial.

La vista conserva únicamente copias privadas. Los buffers anteriores dejan de
retenerse al salir del historial; no se reciclan bitmaps que otro estado o el
renderizador pueda seguir utilizando. Al desmontar la vista se cancelan el
trabajo pendiente, el gesto provisional y las animaciones. Un token de generación
impide que un resultado anterior sustituya una imagen recién cargada o limpiada.

El documento de edición reside en memoria. La persistencia de proyectos y su
restauración tras recrear la pantalla corresponden al contenedor/persistencia de
los siguientes módulos; los bitmaps completos no deben introducirse en un Bundle.

## Verificación

Verificado el 9 de noviembre de 2026 en emulador Android 35 x86_64:

- Teléfono 1080 × 1920, densidad 420 y fuente 1,0: **35/35 pruebas pasan**.
- Tablet 1600 × 2560, densidad 240, fuente 1,3 y modo oscuro: **35/35 pasan**.
- Revisión visual del componente, carga y tipografía ampliada en capturas nativas.
- Compilación debug/release y APK de pruebas correctos.
- Lint: 0 errores; 27 advertencias en debug y 26 en release.
- Cuatro APK release sin firma de 702.245 bytes cada uno, todos inferiores a 4 MB.

Entre las advertencias se mantienen sugerencias `UseKtx` para llamadas nativas y
el aviso conservador de accesibilidad cuyo `performClick()` se ejecuta en el
helper de fin de gesto. Dos avisos `InlinedApi` corresponden al valor constante
balanceado de LineBreaker: el bytecode contiene el entero 2 y ninguna lectura del
campo de API 29 en tiempo de ejecución. Se utiliza esa constante canónica para
satisfacer el `IntDef` de SDK 35. No se desactivan reglas de lint globalmente.

La revisión física de háptica y la ejecución en API 24–29 quedan fuera de estas
pruebas de emulador. El tamaño de esta base no predice el de la app completa:
R8 puede eliminar APIs aún no llamadas por una pantalla de producción.

Evidencia:

- [Componente nativo](qa/sticker-canvas-component.png)
- [Pantalla de teléfono](qa/sticker-canvas-screen.png)
- [Fuente 130 %](qa/sticker-canvas-font13.png)
- [Estado de carga](qa/sticker-canvas-loading-font13.png)
- [Pantalla de tablet](qa/sticker-canvas-tablet.png)
- [Informe de teléfono](qa/android35-tests.txt)
- [Informe de tablet](qa/android35-tablet-tests.txt)

El host `CanvasPreviewActivity` y el proveedor privado de fixtures viven solo en
`src/debug`. El proveedor comparte el proceso y las dependencias de la app de
depuración; no se registra ningún proveedor de fixtures ni Activity de prueba en
el manifest de producción.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease :app:verifyReleaseApkSize
.\gradlew.bat :app:connectedDebugAndroidTest
```

El runner nativo reúne los 19 casos del Módulo 2 y 16 casos del lienzo: medida,
exportación transparente, propiedad de bitmaps, pan, pinch real, rotación, lazo,
recorte de imagen transformada, borrador, cancelación, texto arrastrable, borde
circular, bloqueo durante carga, regeneración del borde, cancelación de resultados
y capacidad de deshacer. También captura la vista nativa en Android.

La previsualización usa una imagen sintética de depuración. No representa un pack
preinstalado ni añade contenido a la aplicación de producción.

## Referencias

- [Dibujo de vistas personalizadas](https://developer.android.com/develop/ui/views/layout/custom-views/custom-drawing)
- [Composición PorterDuff](https://developer.android.com/reference/android/graphics/PorterDuff.Mode)
- [Háptica de confirmación y compatibilidad de API](https://developer.android.com/reference/android/view/HapticFeedbackConstants)
