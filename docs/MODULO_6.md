# Módulo 6: biblioteca, editor, Photo Picker y portapapeles

La aplicación ya tiene un launcher de producción: `ui/MainActivity.kt`.
`ui/StickerEditorActivity.kt` no está exportada. Ambas usan AppCompat y ViewBinding;
`StickerPackAdapter.kt` utiliza ListView/GridLayout nativos, sin RecyclerView ni
dependencias nuevas. Las cuatro declaraciones `implementation` siguen intactas.

## Biblioteca y creación

La biblioteca muestra título y publisher directamente, con miniaturas de los
WebP registrados. Pulsar una tarjeta selecciona el pack y despliega sus stickers;
pulsar una miniatura abre su edición, y mantenerla pulsada permite eliminarla.
Las acciones Nuevo sticker y Eliminar pack son secundarias. Las eliminaciones
requieren confirmación y usan las versiones del Módulo 4.

El botón primario global Agregar a WhatsApp corresponde al pack seleccionado.
Permanece deshabilitado hasta alcanzar 3 stickers y durante el trabajo activo.
El progreso usa frases como `Faltan 2 stickers para exportar · 1/30` y
`Listo para exportar · 14/30`. Nunca se expone un sticker 31 desde la UI.

Crear pack abre el editor. Al guardar el primer sticker se piden nombre del pack
y publisher. `createPackWithSticker` publica el primer WebP y su tray real de
96 × 96 en una sola transacción de referencias SQLite, siguiendo la publicación
recuperable de archivos del Módulo 4. No se crean packs con íconos genéricos de
relleno para simular contenido.

Las miniaturas se leen en IO y se decodifican a 256 px. Se limita la decodificación
a dos trabajos simultáneos y la caché a 4 MiB; cada fila cancela sus trabajos al
reciclarse. No se reciclan manualmente bitmaps que todavía podrían estar dibujándose.
La caché y sus jobs se liberan al destruir la Activity. Una miniatura pendiente
muestra progreso y una imagen ausente muestra un error funcional.

## Ingesta de imágenes

`ClipboardImages` lee `ClipboardManager.primaryClip` únicamente desde las pantallas
en foco o una acción explícita de pegado. `onWindowFocusChanged(true)` actualiza
el botón Pegar imagen. La presencia de un MIME `image/*` no certifica contenido;
una URI local también puede ser candidata aunque el proveedor declare otro MIME.
Se limita la inspección a ocho items y URI de hasta 4096 caracteres.

Se admiten candidatos `content://` y `file://`. El motor del Módulo 2 impide que
`file://` acceda a rutas ajenas al filesDir/cacheDir privados. No se interpretan
Intent del clip ni se descargan URL de texto HTTP/HTTPS. Para pegar una imagen
copiada en otro programa, ese programa debe publicar una URI de imagen y Android
debe conceder acceso. Un navegador que copie solamente una URL de Internet, o un
teclado que entregue contenido por otro mecanismo, no equivale a una imagen local
legible del portapapeles. La UI explica cómo volver a copiar o elegir desde Fotos.

Las dos pantallas registran `ActivityResultContracts.PickVisualMedia` con
`PickVisualMediaRequest(PickVisualMedia.ImageOnly)`. La selección recibida en Main
se transmite explícitamente al editor privado, dentro del mismo UID. No se añade
acceso global al almacenamiento ni se conserva acceso persistente a originales:
el contenido se importa inmediatamente al pipeline.

**Toda URI externa termina en `ImageSanitizerAndCompressor.createSticker(uri)`
antes de decodificarse para el lienzo.** Se ejecutan los límites de 15.000.000
bytes, firmas JPEG/PNG/WebP, dimensiones, muestreo y recodificación sin metadatos.
Una importación fallida conserva la edición anterior. Ni Main ni el adaptador
decodifican originales externos directamente.

## Editor y peso real

El editor conecta Transformar, Lazo, Borrador, Borde blanco, Texto, Deshacer, Pegar
y Elegir imagen con el canvas del Módulo 3. La barra se desplaza horizontalmente
cuando no caben los objetivos táctiles de 48 dp. El borrador tiene un control de
tamaño. El texto es una capa arrastrable; el diálogo permite sustituirlo o quitarlo
dejando el campo vacío.

El indicador KB deriva de los bytes reales del motor; usa monospace y `tnum`.
Después de una edición, una compresión con espera breve agrupa callbacks del
mismo gesto y vuelve a medir el documento. Mientras calcula se indica el estado
pendiente, sin presentar un peso anterior como vigente.
La medición espera a que termine cualquier gesto iniciado durante esa espera;
cambiar de herramienta por sí solo no modifica el documento ni dispara compresión.
Los estados Sin imagen y Calculando usan la fuente normal; monospace queda
reservado al valor numérico en KB.

`compressStickerBitmap` y `compressTrayBitmap` amplían el Módulo 2 para los píxeles
software que ya pertenecen al editor. Dibujan un bitmap limpio y reutilizan la
misma compresión acotada, purga de chunks y límites de 98.000/48.000 bytes.
Estas APIs no sustituyen la sanitización URI de una entrada externa.

Guardar crea o añade un sticker, o reemplaza el archivo que se abrió para editar.
Las sustituciones usan nombres nuevos y conservan los emojis. Las versiones
obsoletas se rechazan; la UI pide volver a abrir el pack. El repositorio calcula
el tamaño a partir de los bytes y elimina las imágenes retiradas.

## Carga, feedback y ciclo de vida

Sanitización, compresión, guardado, lectura de packs y exportación tienen estados
visibles y bloqueos contra toques dobles. El procesamiento de imagen y archivos
ocurre en IO; los bitmaps que se entregan al canvas se instalan en Main y luego
se libera la copia temporal. Las operaciones nativas de lazo/borde mantienen
sus estados de carga propios y deshabilitan las herramientas de la pantalla.

Se usan `ViewPropertyAnimator` de 180 ms y hápticos CONFIRM/REJECT en API 30+,
con alternativa compatible anterior. La duración respeta la desactivación de
animaciones del sistema. No se usan animaciones decorativas ni bibliotecas.

Las Activities poseen scopes que se cancelan en `onDestroy`. El editor retiene
un `StickerCanvasView.Session` opaco con documento e historial durante cambios de
configuración, sin capturar la Activity anterior. Conserva modo y tamaño de borrador.
El estado guardado incluye una representación WebP acotada de la última versión
preparada para recuperación tras muerte del proceso. Esa recuperación aplana las
capas y pierde el historial; una edición todavía no comprimida puede quedar fuera
del checkpoint y la UI anuncia que recuperó la última versión preparada.

Atrás solicita confirmar cuando hay cambios sin guardar. Una importación se puede
abandonar con Atrás del sistema. Durante la breve confirmación de guardado no se
aceptan nuevas acciones. Una transacción SQL confirmada no se revierte si después
se destruye la pantalla o se cancela la corrutina.

## Confirmación de WhatsApp

El exportador admite un callback `launcher` opcional, ejecutado en Main después
de validar el pack y avanzar su versión. Main lo conecta con
`ActivityResultContracts.StartActivityForResult`, conserva el estado de espera y
deshabilita el CTA hasta recibir el resultado.

Solo `Activity.RESULT_OK` muestra Pack agregado a WhatsApp y feedback positivo.
Cancelación o fallo generan un mensaje distinto y feedback de rechazo; abrir el
Intent por sí solo nunca se comunica como incorporación confirmada. Si no hay
WhatsApp instalado, la UI vuelve a habilitar sus controles con un error recuperable.

## Aplicación del manifiesto visual

| Regla | Aplicación |
| --- | --- |
| 1–2 | Sin badges; título/publisher directos, sin prefijos redundantes |
| 3–5 | Texto normal, monospace solo KB, tokens existentes en values |
| 6 | Miniaturas reales de archivos registrados y tray del primer sticker |
| 7 | Biblioteca como superficie principal; editor con el mayor cuadrado completo que cabe |
| 8–10 | Espaciado de 16/24, progreso accionable, un solo CTA sólido por pantalla |
| 11–15 | Paleta fija, vectores 24/trazo 2, sin sombras, radios 20/8/12 y contraste alto |
| 16–18 | Títulos balanceados sin guionado, íconos centrados, métricas/contadores con `tnum` |
| 19–20 | Carga visible, bloqueo de acciones y feedback nativo |

Hay un límite geométrico a la regla 7: un cuadrado completo de lado igual al ancho
de un móvil 9:16 ocupa como máximo el 56,25 % de toda su pantalla, incluso sin
márgenes ni controles. El requisito literal de más del 75 % para ese cuadrado
es incompatible con conservar la proporción 1:1 y verlo completo. `SquareCanvasHost`
ajusta el cuadrado al ancho y alto disponibles, también en horizontal; no lo
estira ni lo recorta para aparentar otro porcentaje. La superficie de biblioteca
se extiende por la ventana, con padding para que los controles no tapen contenido.

## Verificación

`PresentationChecks.kt` añade pruebas instrumentadas nativas a la regresión previa:
portapapeles, UI de importación hostil, Photo Picker ImageOnly interceptado por
Instrumentation, bloqueo de importaciones dobles, miniaturas, progreso, herramientas,
sesión/undo, guardado, sustitución, versiones obsoletas y fallo de exportación.
Los packs de prueba tienen identificadores únicos y se eliminan al terminar.
Las capturas usan stickers de prueba dibujados en bitmaps nativos y guardados por
el mismo pipeline; no son imágenes de relleno incorporadas al producto.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease :app:verifyReleaseApkSize
.\gradlew.bat :app:connectedDebugAndroidTest
```

Verificado el **10 de noviembre de 2026**: **99 pruebas, 0 fallos** en teléfono
Android 35 x86_64 (1080×1920, 420 dpi), en ese teléfono con fuente al 130 % y
en tablet (1600×2560, 240 dpi, fuente al 130 %, modo oscuro). Son 21 pruebas nuevas
de presentación más las 78 de los módulos anteriores. Incluyen la recreación real
del editor y la actualización de peso después de un gesto que coincide con la
espera de compresión.

Compilación debug, APK instrumentado, release con R8 y control de peso correctos.
Lint: **0 errores**, con 25/24 advertencias en debug/release, conservadas visibles.
Los cuatro APK release sin firma pesan **1.138.440 bytes** cada uno. El manifest
final solo incorpora el permiso interno de AndroidX; no añade permisos de red o
almacenamiento. El editor y los proveedores de fixtures permanecen separados:
el primero es privado en producción y los segundos solo existen en debug.

Los informes y capturas son de emulador. El portapapeles se ejercita con ClipData
y concesiones URI reales de Android; el proveedor de fixtures de debug permite
esas concesiones expresamente. El provider de producción mantiene
`grantUriPermissions=false` y su allowlist. El Photo Picker se prueba interceptando
su Intent ImageOnly mediante Instrumentation y devolviendo una URI; no se afirma
una prueba manual con todas las galerías, Gboard o versiones de WhatsApp.

- [Pruebas de teléfono](qa/android35-module6-tests.txt)
- [Pruebas con fuente al 130 %](qa/android35-module6-font13-tests.txt)
- [Pruebas de tablet](qa/android35-module6-tablet-tests.txt)
- [Entorno, APK y manifest](qa/module6-verification.txt)
- [Biblioteca de teléfono](qa/main-module6-phone.png)
- [Biblioteca con texto ampliado](qa/main-module6-font13.png)
- [Pegado y estado vacío con texto ampliado](qa/main-clipboard-module6-font13.png)
- [Editor de teléfono](qa/editor-module6-phone.png)
- [Editor con texto ampliado](qa/editor-module6-font13.png)
- [Biblioteca de tablet](qa/main-module6-tablet.png)
- [Editor de tablet](qa/editor-module6-tablet.png)

Queda pendiente comprobar la confirmación dentro de WhatsApp/Business reales y
ejecutar esta regresión en API 24–34. Los stickers de caras de las capturas son
fixtures generadas y guardadas durante la prueba; el rectángulo rojo del editor
es otra fixture de seguridad que comprueba centrado y transparencia.

## Referencias

- [Photo Picker y fallback del sistema](https://developer.android.com/training/data-storage/shared/photo-picker)
- [Portapapeles y transferencia de URI](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)
- [API ActivityResultRegistry](https://developer.android.com/training/basics/intents/result)
