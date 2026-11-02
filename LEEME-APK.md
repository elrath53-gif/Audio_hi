# Estudio Master Hi-Res — App Nativa Independiente Android (.apk)

Este proyecto contiene **Estudio Master Hi-Res** (`applicationId = "com.estudiomaster.hires"`), configurado como una **aplicación 100% independiente** para Android (se instala aparte en tu celular con su propio icono de cristal traslúcido, sus propios presets y su propio motor, **sin sobrescribir ni borrar tu app anterior `Estudio EQ`**):

- **Identidad independiente (`com.estudiomaster.hires`) + Icono Prisma Cristal Traslúcido:** Convive lado a lado con `Estudio EQ` en la pantalla de inicio y cumple las reglas de paquete e icono único para Google Play Store.
- **Motor Mastering Ultra 64-bit Float64 + Oversampling 4× + Brickwall 2.5 ms:** EQ de 30 bandas ISO, 16 bandas de claridad con Q Proporcional, instrumentos, compresor multibanda, de-esser dinámico, excitador de aire 4×, válvula triodo, centrado de subgraves, crossfeed binaural y transición Gapless / Crossfade de doble lector.
- **Modo Directo Hi-Res para puerto Jack 3.5 mm (`AudioDeviceCallback` + `96/192 kHz` + `TPDF 24-bit`):** Detecta en vivo cuando conectas audífonos o cable auxiliar al puerto Jack 3.5 mm (o adaptador DAC USB-C), desbloquea el reloj de salida a 96 kHz / 192 kHz y activa dithering analógico triangular de 24 bits.
- **Escaneo automático (`MediaStore` + `ContentObserver`) y 2° plano real (`ForegroundService` + `WakeLock` + `MediaSession`):** Carga sola tus pistas FLAC, WAV, AIFF, OPUS, M4A y MP3 y sigue sonando con pantalla apagada.

---

## Cómo obtener tu archivo `Estudio-Master-Hi-Res.apk` sin afectar tu app anterior

1. Crea un **repositorio nuevo** en GitHub (por ejemplo `estudio-master-hires` en [github.com/new](https://github.com/new)) para conservar intacto el repositorio de tu app anterior, y exporta este proyecto desde AI Studio o sube el contenido del ZIP descargado desde la pestaña *Música*.
2. Entra a la pestaña **Actions** de ese nuevo repositorio: verás que **Compilar APK Estudio Master Hi-Res** arranca solo y tarda ~3 minutos.
3. Cuando aparezca en verde ✅, entra a **Releases** en tu repositorio y descarga **`Estudio-Master-Hi-Res.apk`**. Al instalarlo en tu teléfono verás que Android dice **«¿Deseas instalar esta aplicación?»** (en lugar de *Actualizar*), quedando las dos apps instaladas por separado.
