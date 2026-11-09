@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.arya.messenger.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import dev.arya.messenger.data.AppRepository
import dev.arya.messenger.data.AryaNsd
import dev.arya.messenger.data.IdentityEntity
import dev.arya.messenger.data.LanPeerTransport
import dev.arya.messenger.ui.repoScope
import kotlinx.coroutines.launch

@Composable
fun LanScreen(
    repo: AppRepository,
    identity: IdentityEntity,
    lan: LanPeerTransport,
    navController: NavHostController
) {
    val context = LocalContext.current
    var host by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var selectedPeer by remember { mutableStateOf<AryaNsd.Peer?>(null) }
    var discovering by remember { mutableStateOf(false) }
    val peers = remember { mutableStateListOf<AryaNsd.Peer>() }
    var stopDiscovery by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val addresses = remember(lan) { lan.localIpv4Addresses() }

    DisposableEffect(Unit) {
        onDispose { stopDiscovery?.invoke() }
    }

    fun toggleDiscovery() {
        if (discovering) {
            stopDiscovery?.invoke()
            stopDiscovery = null
            discovering = false
        } else {
            peers.clear()
            selectedPeer = null
            discovering = true
            stopDiscovery = AryaNsd(context).discover { list ->
                peers.clear()
                peers.addAll(list)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ارتباط در شبکه محلی") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("هشدار امنیتی", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(
                            "این نسخه پیام را به‌صورت متن ساده روی Wi-Fi می‌فرستد. فقط روی شبکه مورد اعتماد آزمایش کن؛ هنوز رمزنگاری یا احراز هویت همتا وجود ندارد.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
            item {
                Text("کشف خودکار دستگاه‌های آریا", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    "با NSD دستگاه‌های آریا روی همین Wi-Fi را خودکار پیدا کن؛ نیازی به وارد کردن دستی IP نیست.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { toggleDiscovery() }) {
                                Text(if (discovering) "توقف کشف" else "شروع کشف")
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (discovering) "در حال جست‌وجو…" else "کشف خاموش",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (discovering) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (peers.isEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (discovering) "هنوز دستگاه آریایی در این شبکه پیدا نشد؛ مطمئن شو برنامه روی گوشی مقصد هم باز است." else "برای یافتن همتایان، کشف را شروع کن.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            items(peers, key = { it.name + it.host }) { peer ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                            .clickable {
                                selectedPeer = peer
                                host = peer.host
                                status = "گوشی «${peer.name}» انتخاب شد (${peer.host})"
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(peer.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(peer.host, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(peer.host))
                            status = "IP کپی شد: ${peer.host}"
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "کپی IP") }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
            item { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
            item { Text("ارسال به گوشی دیگر", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; selectedPeer = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("IP گوشی مقصد") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("متن آزمایشی") },
                    minLines = 2,
                    maxLines = 4
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Button(
                    onClick = {
                        val target = host.trim()
                        val body = message.trim()
                        if (target.isBlank() || body.isBlank()) return@Button
                        scope.launch {
                            status = "در حال اتصال…"
                            val result = lan.send(target, identity.displayName, identity.publicKey, body)
                            result.onSuccess {
                                val peerName = selectedPeer?.takeIf { it.host == target }?.name
                                val conversationId = repo.getOrCreateLanConversation(target, peerName)
                                repo.saveLanOutgoing(conversationId, body)
                                status = "پیام از طریق TCP ارسال شد و ACK دریافت شد. گفتگو ذخیره شد."
                                message = ""
                            }.onFailure { status = "ارسال ناموفق: ${it.message ?: "خطای شبکه"}" }
                        }
                    },
                    enabled = host.isNotBlank() && message.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ارسال پیام واقعی") }
            }
            item { Spacer(Modifier.height(10.dp)) }
            item {
                if (status.isNotBlank()) {
                    Text(
                        status,
                        fontSize = 12.sp,
                        color = if (status.startsWith("ارسال ناموفق")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                Text("آدرس این گوشی", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                if (addresses.isEmpty()) {
                    Text("آدرس IPv4 شبکه پیدا نشد. Wi-Fi را بررسی کن.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(addresses) { ip ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$ip:${LanPeerTransport.PORT}", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(ip)) }) { Text("کپی IP") }
                }
            }
            item { Spacer(Modifier.height(14.dp)) }
            item {
                Text(
                    "هر دو گوشی باید روی یک شبکه Wi-Fi باشند و پیام‌رسان آریا روی هر دو باز باشد. کشف NSD روی همان شبکه محلی انجام می‌شود و اتصال دستی با IP هم همچنان کار می‌کند. پورت TCP ${LanPeerTransport.PORT} استفاده می‌شود.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
