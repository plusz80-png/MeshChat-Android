# MeshChat Android

Proyecto Android nativo para mensajería privada y comunicación híbrida.

## Decisiones del proyecto

- Kotlin y Jetpack Compose.
- Android 10 o superior (minSdk 29).
- Registro abierto y comunidades privadas por invitación.
- Contactos mediante QR.
- Cifrado de extremo a extremo (E2EE).
- Transportes previstos: Bluetooth LE, Wi-Fi Direct y retransmisión Mesh voluntaria.
- Internet como ruta alternativa cuando no exista una ruta local.
- Selección automática o manual/combinada de conexiones.
- LoRa queda como ampliación futura mediante hardware externo.

## Estado real de la implementación

La aplicación sigue siendo un prototipo. En la rama `feature/opaque-relay-packet-v1` ya existen:

- Una pantalla Compose inicial.
- Un codec binario de paquetes Mesh, independiente del transporte.
- Una política de admisión y deduplicación de paquetes en cada repetidor.
- Un almacén SQLite persistente y limitado que guarda únicamente hashes y caducidades.
- Pruebas JVM para el formato y la política de repetición.
- Pruebas instrumentadas Android previstas para persistencia, caducidad, límite de capacidad y concurrencia.

**Todavía no están implementados** el cifrado E2EE, la autenticación de identidades, registro, comunidades, QR, BLE, Wi-Fi Direct, descubrimiento de pares, encaminamiento real ni retransmisión por Internet. No utilizar para mensajes privados reales.

## Arquitectura de seguridad

- Las claves privadas y el estado criptográfico deben permanecer en los dispositivos finales.
- Los repetidores y servidores solo transportarán paquetes opacos; no tendrán claves privadas, estado MLS ni texto en claro.
- El almacén de repetición guarda un hash y una caducidad, nunca el contenido del mensaje.
- La retransmisión será voluntaria, visible y detenible por el usuario.
- Los permisos y procesos en segundo plano deben ajustarse a las restricciones de Android.

## Compilar y probar

El repositorio todavía no incluye Gradle Wrapper. Abrir en Android Studio con JDK 17 y Android SDK 35, o instalar Gradle 8.9 y ejecutar:

```bash
gradle testDebugUnitTest assembleDebug
gradle connectedDebugAndroidTest
```

La segunda orden necesita un emulador o dispositivo conectado. GitHub Actions ejecuta estas tareas en CI; los resultados deben revisarse en la pestaña Actions.

## Documentación

- [Formato de paquete y repetición](docs/protocol/relay-packet-v1.md)
- [Límite de seguridad E2EE y repetidores](docs/adr/0001-e2ee-boundary-and-relays.md)
- [Permisos y segundo plano Android](docs/android/permissions-and-background.md)
- [Evaluación preliminar de MLS](docs/security/mls-implementation-evaluation.md)
