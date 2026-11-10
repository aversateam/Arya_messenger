@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.arya.messenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.arya.messenger.data.AppRepository
import dev.arya.messenger.data.ContactEntity
import dev.arya.messenger.data.NetworkConfig
import dev.arya.messenger.security.IdentityQr
import dev.arya.messenger.ui.repoScope
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(repo: AppRepository, config: NetworkConfig, navController: NavHostController) {
    val contacts by repo.contacts().collectAsStateWithLifecycle(initialValue = emptyList())
    var status by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ContactEntity?>(null) }
    val scope = rememberCoroutineScope()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw != null) {
            scope.launch {
                runCatching {
                    val card = IdentityQr.decode(raw)
                    repo.addContact(card.displayName, card.publicKey, card.fingerprint)
                    // Reachability hints are optional and unauthenticated; the identity is not.
                    config.setPeerHint(card.publicKey, card.endpoint, card.relay)
                    card.relay?.let { relay ->
                        if (relay !in config.relayEndpoints) {
                            config.relayEndpoints = config.relayEndpoints + relay
                        }
                    }
                }.onSuccess { status = "مخاطب با موفقیت به فهرست اضافه شد." }
                 .onFailure { status = it.message ?: "QR معتبر نیست." }
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مخاطبان آریا") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                "برای افزودن مخاطب، QR هویت عمومی او را اسکن کن. سپس اثر انگشت را از راهی مستقل (مثلاً تماس حضوری یا تماس صوتی) مقایسه کن.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = {
                val options = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("اسکن QR هویت آریا").setBeepEnabled(false).setOrientationLocked(false)
                scanner.launch(options)
            }, modifier = Modifier.fillMaxWidth()) { Text("اسکن QR و افزودن مخاطب") }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("فهرست مخاطبان (${contacts.size})", fontWeight = FontWeight.Bold)
            if (contacts.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f)) {}
                    Text("هنوز مخاطبی اضافه نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f)) {}
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(contacts, key = { it.id }) { contact ->
                        ContactCard(contact, onVerify = { pending = contact })
                    }
                }
            }
        }
    }
    pending?.let { contact ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("تأیید هویت مخاطب") },
            text = {
                Text(
                    "آیا اثر انگشت کامل زیر را از یک مسیر مستقل با خود مخاطب مقایسه کرده‌ای؟ فقط در صورت تطابق کامل تأیید کن.\n\n${contact.fingerprint}"
                )
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.verifyContact(contact.id); pending = null } }) {
                    Text("بله، تطابق دارد")
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("لغو") }
            }
        )
    }
}

@Composable
private fun ContactCard(contact: ContactEntity, onVerify: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(contact.displayName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(
                    if (contact.verified) "تأییدشده" else "تأییدنشده",
                    color = if (contact.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                contact.fingerprint.chunked(24).joinToString("\n"),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
            if (!contact.verified) {
                TextButton(onClick = onVerify) { Text("مقایسه کردم؛ تأیید هویت") }
            }
        }
    }
}
