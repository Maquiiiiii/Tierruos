# Tierruos 0.1.1

- Nombre y publisher opcionales en Crear pack. Al guardar se aplica trim e
  ifBlank con U+2800 (Braille Pattern Blank), manteniendo el contrato SQLite.
- No hay validación de campos obligatorios ni autor rellenado por defecto.
- Las tarjetas ocultan los dos textos cuando contienen U+2800.
- Pegar imagen está centrado bajo el CTA, con separación de 8 dp. Se eliminó
  su duplicado del editor. El footer de biblioteca reserva espacio para ambos botones.
- Diálogo oscuro con superficie #18181C, radio 20 dp y confirmación verde.
- Logo PNG con transparencia exterior obtenido de la imagen adjunta; recursos
  WebP mdpi–xxxhdpi, adaptive icon y capa monocromática para Android 13+.
- versionCode 2, versionName 0.1.1. Se conserva la clave de firma de 0.1.0.

La app conserva U+2800 literalmente en SQLite y lo entrega mediante el provider.
La presentación final de un nombre visualmente vacío depende de WhatsApp; no se
afirma una prueba de confirmación en ese cliente. Los informes anteriores de los
módulos corresponden a sus versiones originales.

Validación: assembleRelease, lintRelease y verificación de tamaño correctos; 101 pruebas instrumentadas sin fallos en Android 35 de teléfono. APK universal firmado: 1.267.635 bytes, inferior a 4 MB. Firma v2/v3 y alineación verificadas.

SHA-256: `25950529ca4a1d7765630908ae880e6be2057cfaea51a0c5be5ea5df5d8421b6`.

[Descargar APK 0.1.1](https://github.com/Maquiiiiii/Tierruos/releases/download/v0.1.1/Tierruos-universal.apk).
