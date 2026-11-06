# Android permissions and background execution plan

Status: design checklist only. No BLE, Wi-Fi Direct or background relay service is implemented.

## Principle

Request a permission only when the user enables the feature that needs it. Explain the reason in the UI, handle denial without crashing, and provide a foreground-only fallback. Do not request background location merely to keep a mesh network alive.

## Bluetooth LE

| Android version | Planned permissions | Runtime behavior |
|---|---|---|
| Android 10–11 (API 29–30) | Legacy \`BLUETOOTH\` and \`BLUETOOTH_ADMIN\`; location permission when required for scanning | Request location only when the scan flow needs it; stop scan when the user exits or disables discovery |
| Android 12+ (API 31+) | \`BLUETOOTH_SCAN\` for discovery; \`BLUETOOTH_CONNECT\` when connecting; \`BLUETOOTH_ADVERTISE\` only if this phone advertises | These are Nearby devices runtime permissions; request only the subset required by the chosen mode |

Use \`neverForLocation\` on \`BLUETOOTH_SCAN\` only if the app genuinely never derives physical location from scan results. Do not add \`BLUETOOTH_ADVERTISE\` until the device-advertising role is implemented.

## Wi-Fi Direct

The transport will need \`INTERNET\`, \`ACCESS_NETWORK_STATE\`, \`ACCESS_WIFI_STATE\` and \`CHANGE_WIFI_STATE\`. For Android 13+ (API 33+), declare and request \`NEARBY_WIFI_DEVICES\` for peer discovery/connection. The Android Wi-Fi Direct guide also documents location permission requirements on older platform versions; verify the exact target-SDK and API-level combination before changing the manifest. Do not request location for unrelated chat use.

The current manifest is only a scaffold. In particular, its existing location declaration is not yet a validated Wi-Fi Direct permission matrix for API 29–32.

## Background and voluntary relay

- Default mode is foreground-only. No hidden always-on scanning or relay.
- A user must explicitly enable relay mode from a visible screen. Show a persistent notification with a clear stop action while an eligible foreground service is active.
- On Android 12+ (API 31+), foreground services generally cannot be started while the app is in the background except for documented exemptions. Start an eligible session from a visible user action.
- On Android 14+ (API 34+), declare the correct foreground-service type and its matching permission, and satisfy its runtime prerequisites. Choose the type only after the actual transport/service design is implemented and reviewed.
- Do not use a \`dataSync\` foreground service as a permanent always-on mesh daemon. Android 15 adds time limits to data-sync foreground services.
- Use WorkManager for deferrable maintenance (for example, cleanup or retry scheduling), not as a promise of continuous low-latency BLE discovery or message delivery.
- When the app is stopped, permissions are denied, battery restrictions intervene, or the user disables relay, the device must stop volunteering as a repeater. Messages can wait for another route.

## Implementation and test gates

1. Add permissions with \`maxSdkVersion\` and \`usesPermissionFlags\` only where justified by the selected API behavior.
2. Add runtime permission rationale and denial/revocation handling.
3. Add a foreground-session state machine with explicit start/stop and notification actions.
4. Measure scan duty cycle, wakeups, battery use and data use; use bounded retries/backoff.
5. Test BLE and Wi-Fi Direct across Android 10, 11, 12, 13, 14 and 15+ on real devices from at least two vendors.
6. Verify that a relay process handles only opaque packets and stops promptly when the user turns it off.

## Official Android references

- Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Wi-Fi Direct: https://developer.android.com/develop/connectivity/wifi/wifip2p
- Foreground-service background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Foreground-service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Foreground-service changes by Android version: https://developer.android.com/develop/background-work/services/fgs/changes
