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
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arya.messenger.AryaApplication
import dev.arya.messenger.data.AppRepository
import dev.arya.messenger.data.AryaNsd
import dev.arya.messenger.data.ContactEntity
import dev.arya.messenger.data.LanPeerTransport
import dev.arya.messenger.data.NetworkConfig
import dev.arya.messenger.network.DeliveryOutcome
import dev.arya.messenger.network.FailureReason
import dev.arya.messenger.network.LanTransportAdapter
import dev.arya.messenger.network.NetworkRuntime
import dev.arya.messenger.network.PeerAddress
import dev.arya.messenger.network.PeerRef
import dev.arya.messenger.network.RouteKind
import dev.arya.messenger.network.relay.RelayProtocol
import dev.arya.messenger.ui.repoScope
import kotlinx.coroutines.launch

@Composable
fun LanScreen(
    repo: AppRepository,
    network: NetworkRuntime,
    config: NetworkConfig,
    navController: NavHostController
) {
    val context = LocalContext.current
    val app = context.applicationContext as? AryaApplication
    val contacts by repo.contacts().collectAsStateWithLifecycle(initialValue = emptyList())
    val verifiedContacts = contacts.filter { it.verified && !it.publicKey.isNullOrBlank() }
    var recipient by remember { mutableStateOf<ContactEntity?>(null) }
    var addressIsInternet by remember { mutableStateOf(false) }
    var relayInput by remember { mutableStateOf(config.relayEndpoints.firstOrNull().orEmpty()) }
    var endpointInput by remember { mutableStateOf(config.publicEndpoint) }
    var nodeMode by remember { mutableStateOf(config.nodeModeEnabled) }
    var host by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var discovering by remember { mutableStateOf(false) }
    val peers = remember { mutableStateListOf<AryaNsd.Peer>() }
    var stopDiscovery by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val lan = remember(network) { network.transport(RouteKind.LAN_TCP) as? LanTransportAdapter }
    val addresses = remember(lan) { lan?.localIpv4Addresses().orEmpty() }

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
                            "ارسال پیام فقط پس از تأیید متقابل هویت مخاطب انجام می‌شود و محتوا در نشست رمزنگاری‌شده منتقل می‌شود. مسیرهای اینترنتی این نسخه هنوز روی دستگاه واقعی تأیید نشده‌اند و رله فقط نشست را جابه‌جا می‌کند؛ چیزی ذخیره نمی‌شود.",
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
            item { Text("گیرنده", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            item {
                Text(
                    "برای مسیر اینترنتی باید مخاطب تأییدشده انتخاب شود؛ رله با شناسه هویت جفت‌سازی می‌کند نه با IP.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (verifiedContacts.isEmpty()) {
                item {
                    Text(
                        "مخاطب تأییدشده‌ای نداری. ابتدا با QR مخاطب را اضافه و اثر انگشت را تأیید کن.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(verifiedContacts, key = { it.id }) { contact ->
                val selected = recipient?.id == contact.id
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clickable { recipient = if (selected) null else contact },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(contact.displayName, Modifier.weight(1f), fontSize = 13.sp)
                        if (selected) {
                            Text("انتخاب‌شده", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
            item { Text("ارسال پیام", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("این آدرس روی اینترنت است", Modifier.weight(1f), fontSize = 12.sp)
                    Switch(checked = addressIsInternet, onCheckedChange = { addressIsInternet = it })
                }
            }
            item {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (addressIsInternet) "آدرس مقصد (host:port، اختیاری)" else "IP گوشی مقصد (اختیاری)") },
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
                    label = { Text("متن پیام") },
                    minLines = 2,
                    maxLines = 4
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Button(
                    onClick = {
                        val body = message.trim()
                        val peer = recipient
                        val addressText = host.trim()
                        if (body.isBlank()) return@Button
                        if (peer == null && addressText.isBlank()) return@Button

                        val addresses = buildList<PeerAddress> {
                            if (addressText.isNotBlank()) {
                                val parsed = RelayProtocol.parseEndpoint(addressText)
                                    ?: RelayProtocol.parseEndpoint("$addressText:${LanPeerTransport.PORT}")
                                if (parsed != null) {
                                    add(
                                        PeerAddress.Tcp(
                                            parsed.host,
                                            parsed.port,
                                            if (addressIsInternet) PeerAddress.Tcp.Scope.INTERNET else PeerAddress.Tcp.Scope.LAN
                                        )
                                    )
                                }
                            }
                            peer?.publicKey?.let { key ->
                                val hint = config.peerHint(key)?.endpoint
                                val parsedHint = hint?.let { RelayProtocol.parseEndpoint(it) }
                                if (parsedHint != null) {
                                    add(PeerAddress.Tcp(parsedHint.host, parsedHint.port, PeerAddress.Tcp.Scope.INTERNET))
                                }
                            }
                        }

                        scope.launch {
                            status = "در حال اتصال…"
                            val outcome = network.send(
                                PeerRef(
                                    publicKey = peer?.publicKey.orEmpty(),
                                    displayName = peer?.displayName.orEmpty(),
                                    addresses = addresses
                                ),
                                body.toByteArray(Charsets.UTF_8)
                            )
                            when (outcome) {
                                is DeliveryOutcome.Delivered -> {
                                    // The handshake proved the peer identity; the dialled IP never does.
                                    repo.recordOutgoing(
                                        outcome.authenticatedPeer.publicKey,
                                        outcome.authenticatedPeer.displayName,
                                        body,
                                        outcome.route
                                    )
                                    status = "پیام رمزنگاری‌شده ارسال شد و تأیید تحویل دریافت شد. گفتگو ذخیره شد."
                                    message = ""
                                }
                                is DeliveryOutcome.Failed -> status = "ارسال ناموفق: ${failureText(outcome.reason)}"
                                is DeliveryOutcome.Queued -> status = "پیام برای ارسال بعدی در صف قرار گرفت."
                            }
                        }
                    },
                    enabled = message.isNotBlank() && (recipient != null || host.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ارسال پیام") }
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
            item { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
            item { Text("مسیر اینترنتی", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            item {
                Text(
                    "آریا هیچ سرور مرکزی ندارد و هیچ هزینه‌ای لازم نیست. آدرس نود رله را خودت تعیین می‌کنی؛ خالی‌بودن آن یعنی فقط مسیر محلی و مستقیم.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = relayInput,
                    onValueChange = { relayInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("آدرس نود رله (host:port)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        if (RelayProtocol.parseEndpoint(relayInput) == null) {
                            status = "آدرس رله معتبر نیست (نمونه: 1.2.3.4:45890)"
                        } else {
                            config.relayEndpoints = listOf(relayInput.trim())
                            relayInput = relayInput.trim()
                            status = "آدرس رله ذخیره شد."
                        }
                    }) { Text("ذخیره رله") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        config.relayEndpoints = emptyList()
                        relayInput = ""
                        status = "رله حذف شد."
                    }) { Text("حذف رله") }
                }
            }
            item {
                OutlinedTextField(
                    value = endpointInput,
                    onValueChange = { endpointInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("آدرس عمومی این دستگاه (اختیاری)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
                )
            }
            item {
                Button(onClick = {
                    val value = endpointInput.trim()
                    if (value.isNotEmpty() && RelayProtocol.parseEndpoint(value) == null) {
                        status = "آدرس عمومی معتبر نیست (نمونه: 203.0.113.5:45871)"
                    } else {
                        config.publicEndpoint = value
                        status = "آدرس عمومی ذخیره شد."
                    }
                }) { Text("ذخیره آدرس عمومی") }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("کمک به دیگران (نود رله)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "اگر آدرس عمومی داری، می‌توانی نشست دیگران را جابه‌جا کنی. محتوا رمزنگاری‌شده است و هیچ پیامی ذخیره نمی‌شود.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = nodeMode, onCheckedChange = {
                        nodeMode = it
                        config.nodeModeEnabled = it
                        app?.applyNetworkConfig()
                        status = if (it) "نود رله روشن شد (پورت ${config.nodePort})." else "نود رله خاموش شد."
                    })
                }
            }
            item { Spacer(Modifier.height(14.dp)) }
            item {
                Text(
                    "مسیرها به ترتیب امتحان می‌شوند: شبکه محلی، اتصال مستقیم اینترنتی، سپس نود رله. رله فقط وقتی کار می‌کند که گیرنده هم به همان نود وصل باشد. اگر هیچ مسیری موفق نشود، پیام ارسال نمی‌شود و ارسال فوری گزارش می‌شود؛ صف آفلاین در فاز بعد اضافه می‌شود.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun failureText(reason: FailureReason): String = when (reason) {
    FailureReason.UNTRUSTED_PEER -> "مقصد در مخاطبان تأییدشده نیست؛ ابتدا QR و اثر انگشت را تأیید کن"
    FailureReason.AUTH_FAILED -> "امضای هویت مقصد معتبر نیست"
    FailureReason.TIMEOUT -> "زمان اتصال به پایان رسید"
    FailureReason.TRANSPORT_UNAVAILABLE -> "هیچ مسیر ارتباطی فعال نیست"
    FailureReason.PAYLOAD_INVALID -> "متن پیام معتبر نیست"
    FailureReason.UNREACHABLE -> "مقصد در دسترس نیست"
}
