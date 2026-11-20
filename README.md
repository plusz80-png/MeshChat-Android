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
- Codec binario de paquetes RelayPacket v1.
- Política de admisión y deduplicación de paquetes en repetidores.
- Almacén SQLite persistente y limitado para hashes y caducidades.
- Fragmentación/reensamblado BLE independiente de Android, con límites de memoria, número de transferencias y expiración.
- Pruebas JVM para codec, repetición y fragmentación BLE.

**Todavía no están implementados** el cifrado E2EE, autenticación de identidades, registro, comunidades, QR, scanner/advertiser/GATT BLE, Wi-Fi Direct, descubrimiento, encaminamiento, cola durable de mensajes ni retransmisión por Internet. No utilizar para mensajes privados reales.

## Arquitectura de seguridad

- Las claves privadas y el estado criptográfico deben permanecer en los dispositivos finales.
- Los repetidores y servidores solo transportarán paquetes opacos; no tendrán claves privadas, estado MLS ni texto en claro.
- El almacén de repetición guarda un hash y una caducidad, nunca el contenido del mensaje.
- El código de fragmentación BLE transporta bytes opacos y no autentica ni cifra.
- La retransmisión será voluntaria, visible y detenible por el usuario.
- Los permisos y procesos en segundo plano deben ajustarse a las restricciones de Android.

## Compilar y probar

El repositorio todavía no incluye Gradle Wrapper. Abrir en Android Studio con JDK 17 y Android SDK 35, o instalar Gradle 8.9 y ejecutar:

```bash
gradle testDebugUnitTest assembleDebug
gradle connectedDebugAndroidTest
```

La segunda orden necesita un emulador o dispositivo conectado.

## CI conocida

La ejecución 31 de GitHub Actions completó correctamente `testDebugUnitTest assembleDebug`, pero el job de `connectedDebugAndroidTest` falló: el emulador reportó un fallo del proceso de instrumentación y 0 tests ejecutados. Por tanto, los tests instrumentados están escritos, pero **no hay resultado aprobado de Android instrumentado**. No se han realizado pruebas físicas.

## Documentación

- [Formato de paquete y repetición](docs/protocol/relay-packet-v1.md)
- [Fragmentación BLE v1](docs/protocol/ble-fragmentation-v1.md)
- [Límite de seguridad E2EE y repetidores](docs/adr/0001-e2ee-boundary-and-relays.md)
- [Permisos y segundo plano Android](docs/android/permissions-and-background.md)
- [Evaluación preliminar de MLS](docs/security/mls-implementation-evaluation.md)
