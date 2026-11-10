# Módulo 5: recursos visuales e IPC de WhatsApp

Implementación nativa XML/Kotlin, con las cuatro dependencias existentes. No añade
permisos de almacenamiento, red, consulta global de paquetes ni bibliotecas gráficas.

## Recursos visuales

`res/values/colors.xml` conserva la paleta del editor y añade los roles
`surface_interactive` y `error`. El rojo representa errores; el único acento para
acciones sigue siendo el verde. No hay gradientes, elevaciones decorativas ni
bordes luminosos.

`dimens.xml` centraliza espaciados de 4/8 dp, separaciones de 16/24 dp, controles
con objetivos táctiles mínimos de 48 dp y las relaciones:

| Token | Valor |
| --- | --- |
| `card_outer_radius` | 20 dp |
| `card_padding` | 8 dp |
| `card_inner_radius` | 12 dp |
| `icon_size` | 24 dp |

Los nombres anteriores `radius_card` y `radius_card_content` siguen como alias
para preservar el editor del Módulo 3. Los grosores de trazo de 2 dp existentes
son medidas ópticas, no espaciados de layout.

`themes.xml` asigna tipografía normal en los roles de texto y controles.
Los títulos usan `sans-serif-medium` para la jerarquía, sin cursiva ni negrita
forzada por `textStyle`. `Widget.Tierruos.Title` aplica salto balanceado y evita
la separación silábica en el TextView; los mismos atributos están presentes en
su TextAppearance. Los widgets de título deben consumir ese estilo.

| Estilo | Uso |
| --- | --- |
| `TextAppearance.Tierruos.Title` | Títulos específicos de la tarea |
| `TextAppearance.Tierruos.Body` | Contenido principal |
| `TextAppearance.Tierruos.Caption` | Información secundaria |
| `TextAppearance.Tierruos.Counter` | Contadores con `tnum`, sin monospace |
| `TextAppearance.Tierruos.TechnicalMetric` | Exclusivamente pesos en KB; monospace y `tnum` |
| `Widget.Tierruos.Button.Primary` | Una acción sólida por pantalla |
| `Widget.Tierruos.Button.Secondary` | Acciones secundarias, estilo por defecto |
| `Widget.Tierruos.Tool` | Herramienta con ícono, descripción accesible y ripple nativo |

`TextAppearance.Tierruos.FileSize` es alias de `TechnicalMetric` por compatibilidad.
Los nueve vectores `ic_create`, `ic_paste`, `ic_lasso`, `ic_eraser`, `ic_transform`,
`ic_text`, `ic_outline`, `ic_undo` e `ic_export` tienen viewport y tamaño 24 × 24,
trazo 2, extremos y uniones redondos. El tinte depende del control que los contiene.

El catálogo XML `DesignPreviewActivity` vive solo en debug. Permite comprobar
tipografía, íconos, botones y carga sin añadir pantallas de producto ni iniciar
una exportación. Su registro no forma parte de release.

## Contrato del ContentProvider

Clase: `com.tierruos.stickers.provider.StickerContentProvider`.
Autoridad calculada con el package de la aplicación, sin habilitar BuildConfig:
`com.tierruos.stickers.stickercontentprovider`.

| Ruta | Respuesta |
| --- | --- |
| `metadata` | Metadatos de todos los packs de 3–30 stickers |
| `metadata/<identifier>` | Cero o una fila de pack exportable |
| `stickers/<identifier>` | Stickers en su orden de inserción |
| `stickers_asset/<identifier>/<filename.webp>` | Descriptor de imagen registrada, solo lectura |

Los borradores incompletos no se publican. Los metadatos se consultan por páginas
internas de 200, sin truncar la lista al tamaño de una página. Los datos se leen
de SQLite en cada petición; no hay caché global de packs.

Columnas exactas de metadatos:

```text
sticker_pack_identifier
sticker_pack_name
sticker_pack_publisher
sticker_pack_icon
android_play_store_link
ios_app_store_link
sticker_pack_publisher_email
sticker_pack_publisher_website
sticker_pack_privacy_policy_website
sticker_pack_license_agreement_website
image_data_version
whatsapp_will_not_cache_stickers
animated_sticker_pack
```

`avoidCache` se convierte en `whatsapp_will_not_cache_stickers`, con valor 0/1;
no se inventa una columna IPC `avoid_cache`. `image_data_version` se devuelve como
cadena decimal. `animated_sticker_pack` vale 0. Enlaces y contactos opcionales
quedan vacíos al no existir esos datos en los modelos.

Columnas de stickers: `sticker_file_name`, `sticker_emoji` (hasta tres, separados
por coma) y `sticker_accessibility_text` (vacío hasta disponer de ese dato).
Una proyección puede elegir columnas conocidas; columnas desconocidas o repetidas
y filtros/ordenación SQL del llamador se rechazan.

### Autorización y archivos

Antes de consultar datos, abrir imágenes o responder MIME identificado, se
comprueba `callingPackage`. Solo se admiten `com.whatsapp`, `com.whatsapp.w4b` y
`com.tierruos.stickers`; `null` y cualquier otro valor producen `SecurityException`.
Android comprueba que ese nombre corresponde al UID llamador. No se toma la
identidad de extras ni se elimina la identidad Binder para saltar la validación.
La allowlist de nombres no constituye un pinning de certificados de WhatsApp.

Android 34+ puede solicitar MIME anónimo: `getTypeAnonymous` devuelve `null` sin
revelar existencia de packs. `getType` identificado devuelve `image/webp` también
para el tray, porque los Módulos 2 y 4 guardan el tray como WebP.
En Android 35 el framework también puede borrar la identidad de consultas MIME
del mismo UID; en ese caso `ContentResolver.getType` devuelve `null`. No se agrega
una excepción a la allowlist para esas llamadas sin identidad. La apertura
autorizada de bytes por las rutas de assets sigue disponible.

El descriptor solo se abre después de comprobar:

1. Identificador y nombre mediante las validaciones estrictas del Módulo 4.
2. Pertenencia del nombre al tray o a los stickers del pack registrado.
3. Rutas canónicas y descendencia estricta de `filesDir/stickers/`, con separador
   final en la comprobación; no se permite un prefijo parecido como `stickers2`.
4. Rechazo de enlaces simbólicos y de archivos no regulares mediante `lstat`.
5. Límites de bytes, tamaño registrado y formato/dimensiones WebP estático.
6. Apertura con `ParcelFileDescriptor.MODE_READ_ONLY` y comprobación de inode,
   dispositivo y tamaño con `fstat` antes de entregar el descriptor.

Las lecturas son acotadas y no cargan píxeles completos para validar dimensiones.
Las ediciones y aperturas comparten el bloqueo del repositorio; un descriptor ya
entregado sigue representando su archivo abierto aunque después se elimine el
nombre. Las llamadas independientes no forman una instantánea global de todo
el pack. La versión comunica las ediciones a WhatsApp.

`insert`, `update`, `delete`, `bulkInsert` y `call` son operaciones no admitidas.
Los llamadores autorizados reciben `UnsupportedOperationException`; los demás
se rechazan primero por identidad. Solo se admite el modo literal `r`.

Registro exacto dentro de `<application>`:

```xml
<provider
    android:name="com.tierruos.stickers.provider.StickerContentProvider"
    android:authorities="${applicationId}.stickercontentprovider"
    android:enabled="true"
    android:exported="true"
    android:grantUriPermissions="false"
    android:readPermission="com.whatsapp.sticker.READ" />
```

El permiso de lectura sigue el ejemplo oficial de WhatsApp y se suma a la
allowlist. No se declara una definición propia de ese permiso de WhatsApp ni se
conceden permisos URI transitivos. Las dos entradas `<queries>` siguen presentes.
R8 conserva el punto de entrada y su constructor público sin argumentos.

## Motor de exportación

`WhatsAppExporter(context)` usa el almacenamiento compartido del proceso.
La API pública es suspendida:

```kotlin
suspend fun export(
    activity: Activity,
    identifier: String,
    target: WhatsAppExporter.Target? = null,
    launcher: ((Intent) -> Unit)? = null,
)
```

`target` puede ser `WHATSAPP` o `BUSINESS`. Sin preferencia, usa la aplicación
disponible; si hay ambas, muestra un chooser con dos Intents de componentes
explícitos. Ninguna aplicación de otro paquete puede capturar el action.

El flujo comprueba paquetes instalados y actividades habilitadas, exportadas y
accesibles; valida de nuevo 3–30 stickers, tray 96 × 96, stickers 512 × 512,
WebP estático y presupuestos exclusivos de 48.000/98.000 bytes. La validación de
archivos y el incremento SQL de `imageDataVersion` comparten el bloqueo del
repositorio. La revisión se incrementa una sola vez y se notifican las tres URI
de cursor. El Intent contiene únicamente los extras del contrato:

```text
action: com.whatsapp.intent.action.ENABLE_STICKER_PACK
sticker_pack_id: <identifier>
sticker_pack_authority: <applicationId>.stickercontentprovider
sticker_pack_name: <name>
```

La lectura/validación ocurre en `Dispatchers.IO`; el lanzamiento, en Main. No se
retiene una Activity en un singleton. Se comprueba que la pantalla siga activa.
Una confirmación SQL que ya terminó permanece guardada si después se cancela la
corrutina, se cierra la pantalla o falla el lanzamiento. No se revierte una
versión ya publicada.

### Carga y resultados

La pantalla debe recoger `exporter.isExporting`, un `StateFlow<Boolean>` compartido
por las instancias, para mostrar progreso y deshabilitar el CTA. Un Mutex global
rechaza exportaciones simultáneas con `ExportInProgressException` y el estado se
restaura en `finally`, también ante errores y cancelación. El código de la pantalla
debe gestionar su scope con el ciclo de vida, sin `GlobalScope`.

Otros errores específicos son `WhatsAppNotInstalledException` y
`WhatsAppUnavailableException`, además de errores de archivos, modelos y versiones
del Módulo 4. Una desinstalación entre resolver y lanzar puede provocar
`ActivityNotFoundException`, que la pantalla debe mostrar como fallo recuperable.

El método termina al abrir la confirmación de WhatsApp. No anuncia que el pack ya
se añadió, ni simula aceptación. Desde el Módulo 6, el callback opcional `launcher`
permite a MainActivity conectarlo con StartActivityForResult. La pantalla espera
RESULT_OK antes de confirmar la incorporación y conserva el estado de carga
hasta recibir ese resultado. Sin callback se mantiene el lanzamiento simple original.

## Verificación

Las pruebas nativas nuevas están en `IpcExportChecks.kt`, integradas con el runner
existente sin añadir dependencias de test. Las fixtures usan identificadores
únicos y eliminan solo sus propios packs, conservando el resto de la base.
El recorder de lanzamiento y la Activity de catálogo existen exclusivamente en debug.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease :app:verifyReleaseApkSize
.\gradlew.bat :app:connectedDebugAndroidTest
adb shell am start -n com.tierruos.stickers/com.tierruos.stickers.ui.DesignPreviewActivity
```

Verificado el **10 de noviembre de 2026**: **78 pruebas sin fallos** tanto en
teléfono Android 35 como en tamaño de tablet con fuente al 130 % y modo oscuro.
Son 21 pruebas nuevas de IPC, exportación y recursos, más las 57 anteriores.
La llamada `content call` desde el UID de adb shell se rechaza con la
`SecurityException` de la allowlist, además del control de permisos del manifest.

Debug, APK de pruebas, release con R8 y control de peso compilan correctamente.
Lint tiene 0 errores y 21/38 advertencias en debug/release; se conservan visibles.
Los cuatro APK release sin firma pesan **721.297 bytes** cada uno. Los recursos
y APIs todavía no usados desde un punto de entrada de producción pueden ser
eliminados por R8; la comprobación de peso no predice el tamaño de la app terminada.

- [Pruebas de teléfono](qa/android35-module5-tests.txt)
- [Pruebas de tablet](qa/android35-module5-tablet-tests.txt)
- [Rechazo IPC desde otro UID](qa/module5-unauthorized-ipc.txt)
- [Entorno, manifest, R8 y límites de la verificación](qa/module5-verification.txt)
- [Captura de teléfono](qa/design-system-module5-phone.png)
- [Captura de fuente al 130 %](qa/design-system-module5-font13.png)
- [Captura de tablet](qa/design-system-module5-tablet.png)

La prueba abre un recorder debug para comprobar el Intent. Queda pendiente
completar la confirmación dentro de WhatsApp y Business reales, así como ejecutar
esta regresión en API 24–34. No se presenta la apertura del Intent como un pack
ya incorporado a WhatsApp.

## Referencias oficiales

- [Contrato de columnas y rutas del provider](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/StickerContentProvider.java)
- [Registro oficial con permiso de lectura](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/AndroidManifest.xml)
- [Action, extras y confirmación de WhatsApp](https://github.com/WhatsApp/stickers/blob/main/Android/app/src/main/java/com/example/samplestickerapp/AddStickerPackActivity.java)
- [Requisitos de stickers y tray](https://github.com/WhatsApp/stickers/blob/main/Android/README.md)
- [Identidad comprobada de callingPackage](https://developer.android.com/reference/android/content/ContentProvider#getCallingPackage())
