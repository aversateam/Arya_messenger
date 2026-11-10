package dev.arya.messenger.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.arya.messenger.AryaApplication
import dev.arya.messenger.data.AppRepository
import dev.arya.messenger.data.ConversationEntity
import dev.arya.messenger.data.ContactEntity
import dev.arya.messenger.data.IdentityEntity
import dev.arya.messenger.data.NetworkConfig
import dev.arya.messenger.security.IdentityQr
import dev.arya.messenger.ui.Routes
import dev.arya.messenger.ui.components.Avatar
import dev.arya.messenger.ui.components.rememberQrBitmap
import dev.arya.messenger.ui.repoScope
import dev.arya.messenger.ui.theme.AryaPrefs
import dev.arya.messenger.ui.theme.ThemeMode
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun HomeScreen(repo: AppRepository, identity: IdentityEntity, config: NetworkConfig, navController: NavHostController) {
    val context = LocalContext.current
    val conversations by repo.conversations().collectAsStateWithLifecycle(initialValue = emptyList())
    val contacts by repo.contacts().collectAsStateWithLifecycle(initialValue = emptyList())
    val themeMode by AryaPrefs.theme.collectAsState(initial = AryaPrefs.themeMode(context))
    var search by remember { mutableStateOf("") }
    var showGroup by remember { mutableStateOf(false) }
    var showIdentity by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(identity.displayName, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("آریا", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("سلام ${identity.displayName}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            IconButton(onClick = { showTheme = true }) { Icon(Icons.Default.Palette, contentDescription = "تم") }
            IconButton(onClick = { navController.navigate(Routes.CONTACTS) }) { Icon(Icons.Default.Person, contentDescription = "مخاطبان") }
            IconButton(onClick = { showIdentity = true }) { Icon(Icons.Default.QrCode, contentDescription = "هویت من") }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("ارتباط اینترنتی", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "بدون سرور مرکزی؛ مسیر مستقیم یا رله. هر دو دستگاه باید هویت یکدیگر را تأیید کرده باشند",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("جست‌وجوی گفتگو") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("گفتگوها", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = { showGroup = true }) { Icon(Icons.Default.GroupAdd, contentDescription = "گروه جدید") }
            IconButton(onClick = { navController.navigate(Routes.CONTACTS) }) { Icon(Icons.Default.Add, contentDescription = "دعوت مخاطب") }
        }
        val filtered = conversations.filter { it.title.contains(search, true) }
        if (filtered.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("هنوز گفتگویی نداری", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("برای شروع، دعوت مخاطب را با QR یا لینک اضافه کن.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                OutlinedButton(onClick = { navController.navigate(Routes.CONTACTS) }) { Text("افزودن مخاطب") }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(filtered, key = { it.id }) { item ->
                    ConversationRow(repo, item, onClick = { navController.navigate(Routes.conversation(item.id)) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    if (showGroup) GroupCreateDialog(
        contacts = contacts.filter { it.verified && !it.publicKey.isNullOrBlank() },
        onDismiss = { showGroup = false },
        onCreate = { title, ids ->
            repoScope {
                runCatching { repo.createGroup(title, ids) }
                    .onSuccess { navController.navigate(Routes.conversation(it)); showGroup = false }
                    .onFailure { Toast.makeText(context, it.message ?: "ساخت گروه ناموفق بود", Toast.LENGTH_LONG).show() }
            }
        }
    )
    if (showIdentity) {
        val endpointDraft = remember { mutableStateOf(config.publicEndpoint) }
        var nodeMode by remember { mutableStateOf(config.nodeModeEnabled) }
        // When node mode is on, this device is its own relay, so the invite advertises the same
        // address it already publishes for direct connections.
        val ownRelay = if (!nodeMode) null else endpointDraft.value.trim().ifBlank { null }
        val invite = IdentityQr.link(
            identity.displayName,
            identity.publicKey,
            endpoint = endpointDraft.value.ifBlank { null },
            relay = ownRelay ?: config.relayEndpoints.firstOrNull()
        )
        val bitmap = rememberQrBitmap(invite)
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showIdentity = false },
            title = { Text("دعوت من") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${identity.displayName} • دعوت آریا", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap.asImageBitmap(),
                            contentDescription = "QR دعوت",
                            modifier = Modifier.size(180.dp)
                        )
                    }
                    Text("اثر انگشت کلید عمومی", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(IdentityQr.fingerprint(identity.publicKey), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = endpointDraft.value,
                        onValueChange = {
                            endpointDraft.value = it
                            config.publicEndpoint = it.trim()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("آدرس قابل دسترس این دستگاه (اختیاری)") },
                        singleLine = true
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("اجازه بده دیگران از من رد شوند", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "این دستگاه روی همان آدرس و پورت بالا نود رله هم می‌شود. محتوا رمزنگاری‌شده است و هیچ پیامی ذخیره نمی‌شود. بدون آدرس معتبر بالا، این گزینه به کار کسی نمی‌آید.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = nodeMode, onCheckedChange = {
                            nodeMode = it
                            config.nodeModeEnabled = it
                            (context.applicationContext as? AryaApplication)?.applyNetworkConfig()
                        })
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(invite)) }) {
                        Text("کپی لینک دعوت")
                    }
                    Text(
                        "QR و لینک دقیقاً یک چیزند؛ هرکدام را با مخاطب به اشتراک بگذاری کافی است. سپس اثر انگشت را از راهی مستقل مقایسه کنید.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showIdentity = false }) { Text("بستن") } }
        )
    }
    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("تم برنامه") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                AryaPrefs.setThemeMode(context, mode)
                                showTheme = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = themeMode == mode, onClick = {
                                AryaPrefs.setThemeMode(context, mode)
                                showTheme = false
                            })
                            Text(mode.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTheme = false }) { Text("بستن") } }
        )
    }
}

@Composable
private fun ConversationRow(repo: AppRepository, item: ConversationEntity, onClick: () -> Unit) {
    val lastMessage by repo.lastMessage(item.id).collectAsStateWithLifecycle(initialValue = null)
    val lastAttachment by repo.lastAttachment(item.id).collectAsStateWithLifecycle(initialValue = null)
    val preview = when {
        lastMessage == null && lastAttachment == null -> "پیامی وجود ندارد"
        lastAttachment != null && (lastMessage == null || lastAttachment!!.createdAt >= lastMessage!!.createdAt) ->
            "📎 ${lastAttachment!!.displayName}"
        else -> lastMessage!!.body
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(item.title, isGroup = item.isGroup)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(preview, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatListTime(item.lastActivity), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                if (item.id.startsWith("dm-")) "اینترنت" else "محلی",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp
            )
        }
    }
}

private fun formatListTime(ts: Long): String {
    val today = Calendar.getInstance()
    val that = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = today.get(Calendar.YEAR) == that.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
    else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
}


@Composable
private fun GroupCreateDialog(
    contacts: List<ContactEntity>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ساخت گروه رمزنگاری‌شده") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it.take(64) }, label = { Text("نام گروه") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Text("فقط مخاطبان تأییدشده قابل انتخاب‌اند", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (contacts.isEmpty()) Text("برای افزودن عضو، ابتدا QR مخاطب را اسکن و اثر انگشتش را تأیید کن.", fontSize = 12.sp)
                else LazyColumn(Modifier.height(220.dp)) {
                    items(contacts, key = { it.id }) { contact ->
                        Row(Modifier.fillMaxWidth().clickable {
                            selected = if (contact.id in selected) selected - contact.id else selected + contact.id
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = contact.id in selected, onCheckedChange = { checked ->
                                selected = if (checked) selected + contact.id else selected - contact.id
                            })
                            Column {
                                Text(contact.displayName, fontWeight = FontWeight.Medium)
                                Text("هویت تأییدشده", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onCreate(title.trim(), selected.toList()) }, enabled = title.isNotBlank()) { Text("ساخت گروه") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("لغو") } }
    )
}
