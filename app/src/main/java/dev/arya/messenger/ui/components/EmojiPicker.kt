package dev.arya.messenger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.arya.messenger.R
import dev.arya.messenger.emoji.EmojiData
import dev.arya.messenger.emoji.EmojiImages
import dev.arya.messenger.emoji.EmojiNames
import dev.arya.messenger.emoji.TelegramEmoji

private val CATEGORY_ICONS = intArrayOf(
    R.drawable.msg_emoji_smiles,
    R.drawable.msg_emoji_cat,
    R.drawable.msg_emoji_food,
    R.drawable.msg_emoji_activities,
    R.drawable.msg_emoji_travel,
    R.drawable.msg_emoji_objects,
    R.drawable.msg_emoji_other,
    R.drawable.msg_emoji_flags
)

private val CATEGORY_LABELS = listOf(
    "احساسات و افراد",
    "طبیعت و حیوانات",
    "غذا و نوشیدنی",
    "فعالیت‌ها",
    "سفر و مکان‌ها",
    "اشیاء",
    "نمادها",
    "پرچم‌ها"
)

/**
 * Emoji picker built on Telegram's emoji system: categories come from EmojiData
 * (dataColored), images from the bundled Telegram emoji.pack, tab icons from the
 * Telegram resources, plus recents, search, skin-tone variants and a backspace key.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EmojiPicker(onPick: (String) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TelegramEmoji.ensureLoaded(context)
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(0) } // 0 = recents, 1..8 = Telegram categories
    var colorTarget by remember { mutableStateOf<String?>(null) }
    val recents by TelegramEmoji.recent.collectAsState()
    val names = remember(context) { EmojiNames.get(context) }
    val scheme = MaterialTheme.colorScheme

    val searching = query.isNotBlank()
    val cells: List<String> = remember(query, category, recents) {
        when {
            searching -> searchEmoji(names, query.trim().lowercase())
            category == 0 -> recents
            else -> EmojiData.dataColored.getOrNull(category - 1)?.let { row -> row.filterNotNull() } ?: emptyList()
        }
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("جست‌وجوی ایموجی") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "پاک کردن") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            TabRow(
                selectedTabIndex = category,
                modifier = Modifier.weight(1f),
                containerColor = Color.Transparent,
                divider = {}
            ) {
                Tab(
                    selected = !searching && category == 0,
                    onClick = { category = 0 },
                    icon = {
                        Icon(
                            painterResource(R.drawable.msg_emoji_recent),
                            contentDescription = "اخیر",
                            tint = if (!searching && category == 0) scheme.primary else scheme.onSurfaceVariant
                        )
                    },
                    text = {}
                )
                CATEGORY_ICONS.forEachIndexed { index, iconRes ->
                    val selected = !searching && category == index + 1
                    Tab(
                        selected = selected,
                        onClick = { category = index + 1 },
                        icon = {
                            Icon(
                                painterResource(iconRes),
                                contentDescription = CATEGORY_LABELS[index],
                                tint = if (selected) scheme.primary else scheme.onSurfaceVariant
                            )
                        },
                        text = {}
                    )
                }
            }
            IconButton(onClick = onBackspace) {
                Icon(Icons.Default.Backspace, contentDescription = "پاک کردن", tint = scheme.onSurfaceVariant)
            }
        }

        if (cells.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (searching) "ایموجی‌ای با «$query» پیدا نشد." else "هنوز ایموجی اخیری ثبت نشده است.",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) {
                items(cells, key = { it }) { code ->
                    EmojiCell(
                        code = code,
                        hasVariants = TelegramEmoji.hasVariants(code),
                        onClick = {
                            val tone = TelegramEmoji.colorFor(TelegramEmoji.baseOf(code))
                            val finalCode = TelegramEmoji.fixEmoji(TelegramEmoji.applyColor(code, tone))
                            TelegramEmoji.addRecentEmoji(context, finalCode)
                            onPick(finalCode)
                        },
                        onLongClick = {
                            when {
                                !searching && category == 0 -> TelegramEmoji.clearRecentEmoji(context)
                                TelegramEmoji.hasVariants(code) -> colorTarget = TelegramEmoji.baseOf(code)
                            }
                        }
                    )
                }
            }
        }
    }

    colorTarget?.let { base ->
        AlertDialog(
            onDismissRequest = { colorTarget = null },
            title = { Text("تن پوست") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TelegramEmoji.SKIN_TONES.forEach { tone ->
                        val code = base + tone
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .combinedClickable(
                                    onClick = {
                                        TelegramEmoji.setColor(context, base, tone)
                                        val finalCode = TelegramEmoji.fixEmoji(code)
                                        TelegramEmoji.addRecentEmoji(context, finalCode)
                                        onPick(finalCode)
                                        colorTarget = null
                                    },
                                    onLongClick = {}
                                )
                                .padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            EmojiImage(code, 30.dp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { colorTarget = null }) { Text("بستن") } }
        )
    }
}

private fun searchEmoji(names: Map<String, String>, query: String): List<String> {
    if (query.isEmpty()) return emptyList()
    val out = LinkedHashSet<String>()
    for (j in EmojiData.dataColored.indices) {
        val row = EmojiData.dataColored[j] ?: continue
        for (code in row) {
            if (code == null) continue
            val keywords = names[code]
            if (code.contains(query) || (keywords?.contains(query) == true)) out.add(code)
            if (out.size >= 240) return out.toList()
        }
    }
    return out.toList()
}

@Composable
private fun EmojiImage(code: String, size: Dp) {
    val bitmap = remember(code) { EmojiImages.get(code) ?: EmojiImages.get(TelegramEmoji.baseOf(code)) }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = code, modifier = Modifier.size(size))
    } else {
        Spacer(Modifier.size(size))
    }
}

@Composable
private fun EmojiCell(code: String, hasVariants: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        EmojiImage(code, 32.dp)
        if (hasVariants) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
            )
        }
    }
}
