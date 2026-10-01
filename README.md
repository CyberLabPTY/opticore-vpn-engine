# OptiCore

OptiCore reúne una interfaz web/PWA y un motor Android local.

## Fuente única de interfaz
La carpeta `web/` es la fuente canónica del panel. El build Android la empaqueta para el servidor local de la app, de modo que web, PWA y panel Android compartan la misma interfaz.

## Capacidades
- Diagnóstico de conectividad y métricas que el navegador expone.
- En la app Android: RAM disponible, ZRAM/swap, frecuencias CPU/GPU, temperatura de batería, interfaz activa y diagnóstico DNS.
- Limpieza controlada de cachés externas únicamente cuando Android y los permisos lo permiten.
- Captura y mejora local de imágenes mediante APIs web.

OptiCore no afirma poder forzar RAM, modificar governors de CPU/GPU, cambiar el firmware de cámara ni borrar datos privados de otras apps cuando Android lo impide.
