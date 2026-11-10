# Tierruos

## Platform
Android nativo, Kotlin y XML con ViewBinding, minSdk 24 y target/compileSdk 35.

## Producto
Aplicación de código abierto para preparar stickers estáticos y crear packs de
3–30 imágenes para WhatsApp y WhatsApp Business. Los borradores pueden tener menos.
Importa imágenes locales desde Photo Picker o portapapeles con acceso concedido
por Android. Toda URI pasa por validación de bytes, dimensiones y recodificación.
No descarga enlaces HTTP ni solicita acceso global al almacenamiento.

## Restricciones confirmadas
Cuatro dependencias directas existentes; sin Compose, Room ni cargadores gráficos.
SQLiteOpenHelper y filesDir privados. Presupuesto release menor de 4.000.000 bytes.
Paleta oscura y reglas visuales fijadas por el usuario; el contenido del sticker
domina la interfaz. La incorporación de un pack requiere confirmación en WhatsApp.

## Superficies
Modo Operate: biblioteca de packs y editor de sticker. Biblioteca con producto real,
selección de pack y un CTA de exportación. Editor cuadrado de 512 px con herramientas
nativas, peso real y un CTA para guardar. El área de trabajo aprovecha el espacio
disponible; la superficie cuadrada completa está limitada por la geometría del dispositivo.
