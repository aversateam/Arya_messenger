@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.arya.messenger.data.AppRepository
import dev.arya.messenger.data.AttachmentEntity
import dev.arya.messenger.data.ContactEntity
import dev.arya.messenger.data.GroupMemberEntity
import dev.arya.messenger.data.MessageEntity
import dev.arya.messenger.data.NetworkConfig
import dev.arya.messenger.network.DeliveryOutcome
import dev.arya.messenger.network.NetworkRuntime
import dev.arya.messenger.network.PeerAddress
import dev.arya.messenger.network.PeerRef
import dev.arya.messenger.network.RouteKind
import dev.arya.messenger.network.relay.RelayProtocol
import dev.arya.messenger.ui.components.EmojiText
import dev.arya.messenger.ui.components.EmojiPicker
import dev.arya.messenger.ui.components.deleteLastCodepoint
import dev.arya.messenger.ui.components.insertAtCursor
import dev.arya.messenger.ui.repoScope
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private sealed class ChatItem {
    data class Message(val msg: MessageEntity) : ChatItem()
    data class File(val file: AttachmentEntity) : ChatItem()
    data class DayHeader(val label: String) : ChatItem()
}

private fun dayStart(ts: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ts
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun dayLabel(dayStart: Long): String {
    val today = dayStart(System.currentTimeMillis())
    val yesterday = today - 24L * 60 * 60 * 1000
    return when (dayStart) {
        today -> "امروز"
        yesterday -> "دیروز"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dayStart))
    }
}

private fun buildTimeline(messages: List<MessageEntity>, attachments: List<AttachmentEntity>): List<ChatItem> {
    val merged = mutableListOf<Pair<Long, ChatItem>>()
    messages.forEach { merged.add(it.createdAt to ChatItem.Message(it)) }
    attachments.forEach { merged.add(it.createdAt to ChatItem.File(it)) }
    val desc = merged.sortedByDescending { it.first }.map { it.second }
    val out = mutableListOf<ChatItem>()
    var lastDay = Long.MIN_VALUE
    for (item in desc) {
        val ts = when (item) {
            is ChatItem.Message -> item.msg.createdAt
            is ChatItem.File -> item.file.createdAt
            is ChatItem.DayHeader -> 0L
        }
        val day = dayStart(ts)
        if (day != lastDay) {
            out.add(ChatItem.DayHeader(dayLabel(day)))
            lastDay = day
        }
        out.add(item)
    }
    return out
}

@Composable
fun ConversationScreen(
    repo: AppRepository,
    id: String,
    network: NetworkRuntime,
    config: NetworkConfig,
    navController: NavHostController
) {
    val context = LocalContext.current
    val messages by repo.messages(id).collectAsStateWithLifecycle(initialValue = emptyList())
    val attachments by repo.attachments(id).collectAsStateWithLifecycle(initialValue = emptyList())
    val members by repo.groupMembers(id).collectAsStateWithLifecycle(initialValue = emptyList())
    val contacts by repo.contacts().collectAsStateWithLifecycle(initialValue = emptyList())
    var title by remember { mutableStateOf("گفتگو") }
    var isGroup by remember { mutableStateOf(false) }
    var isGroupOwner by remember { mutableStateOf(false) }
    var showGroupMembers by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var showPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) repoScope {
            runCatching { repo.importFile(id, uri) }
                .onFailure { Toast.makeText(context, it.message ?: "خطا در انتخاب فایل", Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(id) {
        title = repo.conversationTitle(id) ?: "گفتگو"
        isGroup = repo.isGroupConversation(id)
        isGroupOwner = repo.isGroupOwner(id)
        listState.scrollToItem(0)
    }
    LaunchedEffect(messages.size, attachments.size) {
        if (listState.firstVisibleItemIndex <= 2) listState.scrollToItem(0)
    }

    val timeline = remember(messages, attachments) { buildTimeline(messages, attachments) }
    val isInternet = id.startsWith("dm-")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            if (isInternet) "اینترنت • یک پیام در هر نشست" else if (isGroup) "گروه رمزنگاری‌شده • فقط محلی" else "ذخیره‌سازی محلی",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                actions = {
                    if (isGroup) IconButton(onClick = { showGroupMembers = true }) {
                        Icon(Icons.Default.Group, contentDescription = "اعضای گروه")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                ) {
                    Row(
                        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showPicker = true }) {
                            Icon(Icons.Default.SentimentSatisfied, contentDescription = "ایموجی")
                        }
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("پیام…") },
                            maxLines = 4,
                            shape = RoundedCornerShape(18.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                        IconButton(onClick = {
                            if (isGroup) Toast.makeText(context, "پیوست گروهی هنوز آماده نیست", Toast.LENGTH_SHORT).show()
                            else picker.launch(arrayOf("image/*", "application/pdf", "text/*", "application/*"))
                        }, enabled = !isGroup) { Icon(Icons.Default.AttachFile, contentDescription = "پیوست") }
                        val text = input.text.trim()
                        if (text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val body = text
                                    input = TextFieldValue("")
                                    if (isGroup) {
                                        repoScope { repo.sendLocal(id, body) }
                                    } else {
                                        repoScope {
                                            // Store first so a failed send never loses typed text.
                                            val messageId = repo.appendOutgoing(id, body)
                                            val peer = repo.peerForConversation(id)
                                            if (peer == null) {
                                                repo.markMessage(messageId, "FAILED · NO INVITE")
                                                return@repoScope
                                            }
                                            val hint = config.peerHint(peer.publicKey)
                                            val addresses = buildList<PeerAddress> {
                                                hint?.endpoint?.let { RelayProtocol.parseEndpoint(it) }
                                                    ?.let { add(PeerAddress.Tcp(it.host, it.port)) }
                                            }
                                            val outcome = network.send(
                                                PeerRef(
                                                    publicKey = peer.publicKey,
                                                    displayName = peer.displayName,
                                                    addresses = addresses
                                                ),
                                                body.toByteArray(Charsets.UTF_8)
                                            )
                                            repo.markMessage(
                                                messageId,
                                                when (outcome) {
                                                    is DeliveryOutcome.Delivered ->
                                                        if (outcome.route == RouteKind.RELAY) "SENT · RELAY" else "SENT · DIRECT"
                                                    is DeliveryOutcome.Failed -> "FAILED · ${outcome.reason}"
                                                    is DeliveryOutcome.Queued -> "QUEUED"
                                                }
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.size(38.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (timeline.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "پیامی وجود ندارد. پیام‌های این صفحه فقط روی دستگاه ذخیره می‌شوند.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                items(timeline, key = { item ->
                    when (item) {
                        is ChatItem.Message -> "m-${item.msg.id}"
                        is ChatItem.File -> "f-${item.file.id}"
                        is ChatItem.DayHeader -> item.label
                    }
                }) { item ->
                    when (item) {
                        is ChatItem.DayHeader -> {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                ) {
                                    Text(
                                        item.label,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        is ChatItem.Message -> MessageBubble(item.msg)
                        is ChatItem.File -> FileBubble(item.file)
                    }
                }
            }
        }
    }

    if (showGroupMembers) {
        GroupMembersDialog(
            members = members,
            contacts = contacts.filter { it.verified && !it.publicKey.isNullOrBlank() },
            canManage = isGroupOwner,
            onDismiss = { showGroupMembers = false },
            onAdd = { ids -> repoScope { runCatching { repo.addGroupMembers(id, ids) }.onFailure { Toast.makeText(context, it.message ?: "خطا در افزودن عضو", Toast.LENGTH_LONG).show() } } },
            onRemove = { member -> repoScope { runCatching { repo.removeGroupMember(id, member.memberId) }.onFailure { Toast.makeText(context, it.message ?: "خطا در حذف عضو", Toast.LENGTH_LONG).show() } } }
        )
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = sheetState
        ) {
            EmojiPicker(
                onPick = { char ->
                    input = insertAtCursor(input, char)
                },
                onBackspace = { input = input.deleteLastCodepoint() },
                modifier = Modifier.fillMaxWidth().height(480.dp)
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: MessageEntity) {
    val outgoing = msg.outgoing
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (outgoing) 18.dp else 6.dp,
        bottomEnd = if (outgoing) 6.dp else 18.dp
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = shape,
            color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = if (outgoing) 0.dp else 1.dp
        ) {
            Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 9.dp)) {
                EmojiText(
                    text = msg.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(msg.createdAt))} • ${msg.status}",
                        fontSize = 9.sp,
                        color = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FileBubble(file: AttachmentEntity) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(Modifier.widthIn(max = 280.dp).padding(start = 8.dp)) {
                    Text(file.displayName, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${file.sizeBytes / 1024} KB • فقط روی این دستگاه",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


@Composable
private fun GroupMembersDialog(
    members: List<GroupMemberEntity>,
    contacts: List<ContactEntity>,
    canManage: Boolean,
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit,
    onRemove: (GroupMemberEntity) -> Unit
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    val activeIds = members.map { it.memberId }.toSet()
    val available = contacts.filter { it.id !in activeIds }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اعضای گروه · ${members.size}") },
        text = {
            Column {
                members.forEach { member ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(member.displayName)
                            Text(if (member.role == "OWNER") "سازنده گروه" else "عضو", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (canManage && member.role != "OWNER") TextButton(onClick = { onRemove(member) }) { Text("حذف") }
                    }
                }
                if (canManage) {
                    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("افزودن مخاطب تأییدشده", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    if (available.isEmpty()) Text("مخاطب تأییدشده جدیدی وجود ندارد.", fontSize = 12.sp)
                    else androidx.compose.foundation.lazy.LazyColumn(Modifier.height(170.dp)) {
                        items(available, key = { it.id }) { contact ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = contact.id in selected, onCheckedChange = { checked ->
                                    selected = if (checked) selected + contact.id else selected - contact.id
                                })
                                Text(contact.displayName)
                            }
                        }
                    }
                } else {
                    Text("فقط سازنده گروه می‌تواند اعضا را تغییر دهد.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("مدیریت اعضا و کلیدها فعلاً محلی است؛ توزیع کلید به دستگاه‌های دیگر در فاز ۶ انجام می‌شود.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            if (canManage && selected.isNotEmpty()) TextButton(onClick = { onAdd(selected.toList()); selected = emptySet() }) { Text("افزودن") }
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}
