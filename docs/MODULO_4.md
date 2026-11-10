# Módulo 4: datos locales y archivos privados

Implementado con `SQLiteOpenHelper`, `ContentValues`, consultas con argumentos
enlazados, archivos internos y las corrutinas ya incluidas. No añade dependencias
ni permisos Android.

## Archivos y modelos

- `data/model/Sticker.kt`: nombre WebP, tamaño decimal en KB y hasta 3 emojis.
- `data/model/StickerPack.kt`: modelo exportable de 3–30 stickers y tipo separado
  `StickerPackDraft` de 0–30 para creación y edición progresivas.
- `data/db/TierruosDatabaseHelper.kt`: esquema y CRUD parametrizado.
- `data/repository/StickerFileRepository.kt`: coordinación de archivos y metadatos.
- `data/repository/WebpAssetValidation.kt`: comprobación acotada de la salida WebP.
- `data/StorageContract.kt`: nombres válidos, generación de IDs, coordinación y errores.

Los modelos incluyen `identifier`, `name`, `publisher`, `trayImageFile`,
`imageDataVersion`, `avoidCache` y `stickers`. `identifier` usa solo ASCII
alfanumérico, de 1 a 128 caracteres. Los IDs generados son UUID sin guiones.
Nombre y publisher son obligatorios, de hasta 128 unidades UTF-16. Los nombres de
imagen son únicos dentro del pack y el tray no comparte nombre con un sticker.

`imageDataVersion` es un `Long` positivo, inicialmente 1. Cada modificación de
metadatos, tray, emojis o stickers lo incrementa dentro de la misma transacción.
El futuro ContentProvider puede proporcionar su representación decimal con
`imageDataVersion.toString()`. `avoidCache` conserva la decisión del llamador;
el contador de versión se actualiza aunque esa opción sea `false`.

`Sticker.sizeInKb` usa KB decimales, como el Módulo 2. SQLite almacena bytes
enteros y los convierte al leer, evitando acumular errores de coma flotante.
El repositorio calcula ese tamaño a partir del archivo real.

Las operaciones toman snapshots de las listas antes de persistirlas y devuelven
listas no modificables al leer SQLite. Los emojis ocupan tres columnas opcionales,
sin serialización ni bibliotecas adicionales. Se admiten secuencias Unicode de
hasta 64 unidades UTF-16 por emoji; la elección de emojis reales pertenece al
selector de la interfaz.

## Esquema SQLite

La base `tierruos.db` vive en el directorio privado de bases de datos de Android.
La versión inicial del esquema es 1.

| Tabla | Propósito |
| --- | --- |
| `packs` | Metadatos, identificador primario, versión y política de caché |
| `stickers` | Nombre único por pack, bytes exactos, tres emojis e ID de inserción |
| `pending_file_deletions` | Trabajo durable de limpieza que sobrevive al borrado del pack |

`stickers.pack_identifier` referencia a `packs.identifier` con
`ON DELETE CASCADE`. Las claves foráneas se activan en `onConfigure`, antes de
las transacciones. El índice por pack e ID mantiene lecturas pequeñas y un orden
de inserción estable. La sustitución completa de un pack conserva el orden de la
lista que recibe.

Hay restricciones para nombres, tamaños, booleanos y versiones, y un trigger
impide insertar el sticker 31. Otros triggers evitan que tray y sticker compartan
nombre y encolan las imágenes retiradas. La cola no tiene FK al pack, para poder
limpiarlo después de su eliminación. Un nombre vacío en esta tabla interna
representa la retirada del directorio completo; nunca es un nombre de imagen
aceptado por las APIs.

Se usa WAL y sincronización `FULL`. Los valores se pasan mediante `ContentValues`,
`selectionArgs` y parámetros `?`, incluidos los de paginación. Los identificadores
SQL y las sentencias de creación son constantes del código.

`onUpgrade` rechaza migraciones no definidas y conserva los datos. No hay una
recreación destructiva del esquema. Un `DatabaseErrorHandler` propio conserva
también una base corrupta y propaga `SQLiteDatabaseCorruptException`, permitiendo
su recuperación posterior en lugar de resetearla automáticamente.

## API y uso

Crear una instancia compartida por proceso de aplicación:

```kotlin
val database = TierruosDatabaseHelper(applicationContext)
val repository = StickerFileRepository(applicationContext, database)
```

Desde una corrutina, con resultados previamente sanitizados por el Módulo 2:

```kotlin
val draft = repository.createDraft("Mis stickers", "Tierruos", sanitizedTray.bytes)
val sticker = repository.addSticker(draft.identifier, sanitizedSticker.bytes, listOf("😀"))
```

El repositorio cambia a `Dispatchers.IO`. El llamador gestiona el estado de carga
de la interfaz hasta que la operación termina. La aplicación es propietaria del
helper y lo cierra cuando deja de utilizar sus repositorios.

| Operación | API del repositorio |
| --- | --- |
| Crear borrador con tray | `createDraft` |
| Leer/listar borradores y packs | `getDraft`, `listPacks` |
| Obtener pack válido con todos sus archivos comprobados | `getExportablePack` |
| Editar nombre, publisher o caché | `updatePackMetadata` |
| Reemplazar tray | `replaceTrayImage` |
| Añadir/reemplazar sticker | `addSticker`, `replaceSticker` |
| Editar emojis | `updateStickerEmojis` |
| Leer bytes privados | `readSticker`, `readTrayImage` |
| Resolver archivo de un sticker registrado | `getStickerFile` |
| Eliminar sticker/pack y limpiar sus archivos | `deleteSticker`, `deletePack` |
| Recuperar trabajo pendiente y retirar huérfanos | `removeOrphanedFiles` |

Los métodos de modificación aceptan opcionalmente `expectedVersion`. Un editor
puede pasar la versión que mostró al usuario; si cambió, recibe
`PackVersionConflictException` y los cambios se revierten. La sustitución completa
mediante `database.updatePack` siempre usa la versión del modelo como condición.

El helper ofrece CRUD síncrono completo con `createPack`, `createDraft`,
`getPack`, `getDraft`, `listPacks`, `updatePack`, `updatePackMetadata`, `deletePack`,
`createSticker`, `getSticker`, `listStickers`, `updateSticker` y `deleteSticker`.
Se utiliza en hilos IO/Binder. El helper administra SQLite y la cola de limpieza;
los cambios de aplicación que incluyen archivos se realizan por el repositorio.
Una eliminación ejecutada directamente con SQL queda encolada y se completa en
el siguiente acceso al repositorio.

## Archivos y contención de rutas

La distribución es:

```text
context.filesDir/
└── stickers/
    └── <identifier>/
        ├── tray_<uuid>.webp
        ├── sticker_<uuid>.webp
        └── <nombre-personalizado>.webp
```

Los nombres personalizados se reciben completos, con extensión `.webp`. Se
comprueban con `^[a-zA-Z0-9_-]+\.webp$`, con un máximo de 128 caracteres, antes
de construir la ruta. No se añade otra extensión al nombre recibido.

El padre privado se normaliza a su ruta canónica. Se verifica cada descendiente
y se consulta `lstat` para rechazar enlaces simbólicos, incluso colgantes. No se
siguen enlaces ni se eliminan árboles recursivos. Los directorios de pack son
planos y únicamente este repositorio debe escribir en `filesDir/stickers`.

Cada raíz queda asociada a una base de datos dentro del proceso. Un bloqueo
reentrante compartido por repositorios y CRUD evita que una limpieza intercale
una escritura todavía no publicada. El contrato presupone un único proceso de
aplicación, como el manifest actual.

Se comprueban también límites exclusivos de 98.000/48.000 bytes, dimensiones
512/96 y un contenedor WebP estático compuesto solo por chunks de píxeles. Esta
comprobación complementa la sanitización destructiva del Módulo 2. No recibe URI
ni sustituye ese pipeline. Las lecturas de disco se acotan al tamaño permitido y
detectan archivos truncados o que crecen durante la lectura.

## Publicación, borrado y recuperación

1. Se prepara un WebP con nombre nuevo dentro del directorio privado del pack.
2. Se escribe un temporal en el mismo directorio y se sincroniza su descriptor.
3. Se publica mediante renombrado y se sincroniza el directorio con APIs nativas.
4. Se confirma la referencia y la versión del pack mediante una transacción SQL.
5. Se drenan las retiradas encoladas por esa transacción, comprobando que ningún
   archivo vuelva a estar referenciado antes de eliminarlo.

Una edición de imagen o tray usa otro nombre. El archivo anterior permanece
intacto hasta confirmar SQLite. Si la transacción falla, se retira el archivo
nuevo solo tras confirmar que no está referenciado. Si la base no permite
comprobar el resultado, se conservan sus bytes para recuperarlos después.
Si se interrumpe el proceso entre publicar el archivo y registrar su
referencia, el primer acceso de la siguiente instancia lo recoge como huérfano.

Los triggers mantienen la tarea de borrado en la misma transacción que retira la
referencia. Un fallo al eliminar no confirma esa tarea. Si un nombre se vuelve a
referenciar, la limpieza lo conserva. El borrado de pack retira también sus
archivos sobrantes y su directorio plano.

SQLite y el filesystem conservan mecanismos de confirmación propios; la
coordinación es ordenada, idempotente y recuperable. Un error de limpieza después
del commit puede propagarse aunque la modificación lógica ya esté confirmada.
El llamador debe releer el estado y puede reintentar `removeOrphanedFiles`.
La cancelación comprueba el estado antes del trabajo y antes de confirmar; una
transacción que ya se confirmó permanece guardada aunque la corrutina se cancele.

Si falta la base y existen archivos, la recuperación se detiene y los conserva.
Lo mismo sucede si la base está corrupta. Si falta una imagen referenciada, se
bloquea su exportación y se mantienen los metadatos para permitir recuperarla.

## Errores y límites

- `SecurityException`: identificador/nombre inválido, ruta no canónica o enlace.
- `IllegalArgumentException`: modelo inválido, emojis o límites de argumentos.
- `PackNotReadyException`: borrador de menos de 3 stickers solicitado para exportar.
- `PackVersionConflictException`: intento de guardar sobre una versión obsoleta.
- `SQLiteConstraintException`: duplicado, FK, colisión de nombres o sticker 31.
- `SQLiteDatabaseCorruptException`: base corrupta preservada.
- `IOException` y errores tipados del Módulo 2: almacenamiento o WebP inválido.

Las colecciones de packs se paginan, de 1 a 200 elementos por consulta; cada
colección de stickers está limitada a 30. No se conserva una caché global de
bitmaps ni se almacenan imágenes como BLOB en SQLite.

## Verificación

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease :app:verifyReleaseApkSize
.\gradlew.bat :app:connectedDebugAndroidTest
```

El runner nativo reúne 19 pruebas de seguridad, 16 de lienzo y 22 de datos. Estas
últimas usan bases con nombres únicos y raíces privadas de prueba, que se limpian
al terminar. Cubren CRUD, SQL Injection, cascada, límite 30, versiones, tamaños,
rutas, enlaces, reemplazos, recuperación, concurrencia, reapertura y preservación
ante pérdida/corrupción de la base.

Verificado el 9 de noviembre de 2026: **57 pruebas, 0 fallos** en emulador Android
35 x86_64. Compilación debug, APK de pruebas, release optimizado con R8 y control
de peso correctos. Lint: 0 errores, 27 advertencias en debug y 26 en release,
sin desactivar comprobaciones. Los cuatro APK release sin firma pesan 702.245
bytes cada uno; aún no hay pantalla ni provider de producción que referencie
estas APIs, por lo que R8 puede eliminarlas de esta base.

Resultados: [runner nativo](qa/android35-module4-tests.txt) y
[entorno y compilación](qa/module4-verification.txt). Las pruebas de este módulo
se ejecutaron en teléfono Android 35; queda pendiente ejecutarlas en API 24–34.

## Referencias oficiales

- [SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper)
- [SQLiteDatabase y argumentos enlazados](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase)
- [DatabaseErrorHandler](https://developer.android.com/reference/android/database/DatabaseErrorHandler)
- [Contrato de stickers de WhatsApp](https://github.com/WhatsApp/stickers/blob/main/Android/README.md)
