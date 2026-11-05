package org.meshchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MeshChatApp() }
    }
}

@Composable
private fun MeshChatApp() {
    var automatic by remember { mutableStateOf(true) }
    var relay by remember { mutableStateOf(false) }
    var ble by remember { mutableStateOf(true) }
    var wifi by remember { mutableStateOf(true) }
    var internet by remember { mutableStateOf(true) }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("MeshChat", style = MaterialTheme.typography.headlineLarge)
                Text("Mensajería privada • prototipo de interfaz")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Conectividad", style = MaterialTheme.typography.titleMedium)
                        SettingRow("Modo automático", automatic) { automatic = it }
                        if (!automatic) {
                            SettingRow("Bluetooth LE", ble) { ble = it }
                            SettingRow("Wi-Fi Direct", wifi) { wifi = it }
                            SettingRow("Internet (ruta alternativa)", internet) { internet = it }
                        } else {
                            Text("Se seleccionarán las rutas disponibles.")
                        }
                        SettingRow("Permitir retransmitir mensajes cifrados", relay) { relay = it }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Comunidades y contactos", style = MaterialTheme.typography.titleMedium)
                        Text("Registro abierto, comunidades privadas por invitación y contactos mediante QR: pendientes de implementar.")
                    }
                }
                Text(
                    "Estado: maqueta inicial. Todavía no incluye registro, cifrado E2E, chat real, QR, BLE, Wi-Fi Direct ni retransmisión Mesh.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = { }) { Text("Crear cuenta (próximamente)") }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
