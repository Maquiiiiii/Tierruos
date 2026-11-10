# Distribución de Tierruos

Versión actual: **0.1.1**, 1.267.635 bytes. [Descarga y validación](ACTUALIZACION_0.1.1.md).

La información de 0.1.0 que sigue se conserva como registro histórico.

La APK instalable se publica como asset de GitHub Releases, separada del historial
de código fuente. El enlace estable del README apunta a `Tierruos-universal.apk`.
Cada versión incluye `SHA256SUMS.txt` para comprobar la descarga.

La APK 0.1.0 firmada pesa **1.143.620 bytes**. Su SHA-256 es:

```text
94c7e382afac580b040449b4e9761aacd185a4fc188ab93431a83df112a12d79
```

La versión 0.1.0 conserva `versionCode=1`, `minSdk=24` y `targetSdk=35`. La APK
universal contiene el código de producción optimizado por R8 y recursos reducidos.
No contiene las Activities ni el proveedor de fixtures de debug.

La firma usa una clave privada de Tierruos con certificado `CN=Tierruos`, RSA
3072 y esquemas APK Signature v2/v3. Su certificado SHA-256 es:

```text
d90991b40d35c59406aa1c1c11648354f91f7664d5f6507e674cc7e5f5e94bf9
```

Se verificaron firma y alineación con Android Build Tools 35.0.0. Además de las
99 pruebas instrumentadas documentadas en el Módulo 6, esta APK release firmada
se instaló en un emulador Android 35 aislado y se abrió su biblioteca y editor
de producción, sin errores de AndroidRuntime.

La clave y su contraseña permanecen en el directorio privado ignorado
`.tools/private-signing/` del equipo de publicación. No deben subirse al
repositorio ni adjuntarse a un release. Conserva una copia de seguridad privada
de ambos para poder firmar futuras actualizaciones con la misma identidad.

La compilación de Gradle sigue generando APK sin firma. Para distribuir una nueva
versión, aumenta `versionCode` y `versionName`, compila release, alinea el APK,
fírmalo con esa misma clave y verifica su firma antes de publicar el asset.

```powershell
.\gradlew.bat :app:verifyReleaseApkSize
apksigner verify --verbose --print-certs Tierruos-universal.apk
Get-FileHash Tierruos-universal.apk -Algorithm SHA256
```

Una instalación de debug usa otra firma. Android no permite actualizarla
directamente con esta APK de distribución; para cambiar entre ambas firmas se
necesita una instalación limpia, que elimina los datos locales de la anterior.
