# MeshChat Android

Scaffold inicial para una aplicación Android nativa de mensajería privada y comunicación híbrida.

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

## Estado actual

Este repositorio contiene únicamente una **maqueta de interfaz y estructura Gradle inicial**. No implementa todavía autenticación, almacenamiento de mensajes, cifrado E2EE, intercambio QR, BLE, Wi-Fi Direct, descubrimiento de pares ni retransmisión Mesh. No se debe considerar seguro ni utilizar para comunicaciones reales.

## Arquitectura de seguridad prevista

- Las claves privadas deben permanecer en los dispositivos.
- Los relés y servidores, si se incorporan, solo transportarán paquetes cifrados opacos.
- Ningún servidor de transporte debe tener acceso a claves privadas ni al texto en claro.
- La retransmisión será voluntaria y configurable.
- Los permisos Android y el funcionamiento en segundo plano deberán ajustarse a las restricciones de cada versión y minimizar batería/datos.

## Compilar

Abrir el proyecto en Android Studio (JDK 17, Android SDK 35) y ejecutar:

```bash
./gradlew assembleDebug
```

La primera compilación puede requerir descargar dependencias. Aún no se ha verificado una compilación completa ni pruebas en dispositivo.


## Primer avance técnico: Relay Packet v1

En la rama \`feature/opaque-relay-packet-v1\` se ha añadido:

- Un contenedor binario versionado para transportar un payload ya cifrado, con ID aleatorio, token de destino opaco, caducidad y límite de saltos.
- Un encabezado autenticado previsto como AAD para el futuro cifrado E2EE. El contador de saltos queda fuera del AAD para que los repetidores puedan reducirlo.
- Validación estricta de tamaños, versión, caducidad positiva y límite de saltos.
- Pruebas unitarias para serialización, manipulación del encabezado, truncamiento, datos sobrantes y agotamiento de saltos.
- Un flujo de GitHub Actions para ejecutar pruebas y compilar el APK de depuración.

**Límite importante:** esto no implementa todavía cifrado, descifrado, autenticación de identidades, chat, retransmisión, BLE, Wi-Fi Direct ni transporte por Internet. El proyecto no debe utilizarse aún para mensajes privados reales. La selección de una biblioteca E2EE compatible con Android sigue pendiente de auditoría e integración.


### Android permissions and voluntary relay

The platform permission and background-execution plan is documented in [docs/android/permissions-and-background.md](docs/android/permissions-and-background.md). It is a design checklist, not an implementation: transport permissions and foreground services will be added only alongside their corresponding user-visible features.
