package dev.arya.messenger.emoji

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Emoji core ported from Telegram's Emoji.java / EmojiData.java.
 *
 * Grid data follows Telegram: categories come from EmojiData.dataColored, while the
 * images are individual PNG files extracted from Telegram's emoji.pack and stored as
 * assets/emoji/<category>/<codepoints>.png (easy to view and replace).
 */
object TelegramEmoji {
    data class Info(val page: Int, val index: Int)

    val SKIN_TONES = listOf("", "\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")

    private const val MAX_RECENT = 48
    private const val PREFS = "arya_emoji"
    private const val KEY_RECENT = "recent_emoji"
    private const val KEY_COUNTS = "recent_emoji_counts"
    private const val KEY_COLORS = "emoji_colors"

    private val DEFAULT_RECENT = listOf("\uD83D\uDE02", "\uD83D\uDE18", "\u2764", "\uD83D\uDE0D", "\uD83D\uDE0A", "\uD83D\uDE01", "\uD83D\uDC4D", "\u263A", "\uD83D\uDE14", "\uD83D\uDE04")

    private val rects = HashMap<String, Info>()
    private val drawableSet = HashSet<String>()

    val emojiUseHistory = HashMap<String, Int>()
    val recentEmoji = ArrayList<String>()
    val emojiColor = HashMap<String, String>()

    @Volatile
    var maxLen: Int = 1
        private set

    @Volatile
    private var loaded = false

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private val _colors = MutableStateFlow<Map<String, String>>(emptyMap())
    val colors: StateFlow<Map<String, String>> = _colors.asStateFlow()

    init {
        // rects mirrors Emoji.java: EmojiData.data[j][i] -> (page = j, index = i)
        val data = EmojiData.data
        for (j in data.indices) {
            val row = data[j] ?: continue
            for (i in row.indices) {
                val code = row[i] ?: continue
                rects[code] = Info(j, i)
            }
        }
        // Drawable set: everything we can resolve to a pack image.
        for (j in EmojiData.dataColored.indices) {
            val row = EmojiData.dataColored[j] ?: continue
            for (code in row) {
                if (code != null && itemInfo(code) != null) drawableSet.add(code)
            }
        }
        for (code in rects.keys) drawableSet.add(code)
        for (base in EmojiData.emojiColoredMap) {
            for (tone in SKIN_TONES) {
                val variant = base + tone
                if (itemInfo(variant) != null) drawableSet.add(variant)
            }
        }
        maxLen = drawableSet.maxOfOrNull { it.length } ?: 1
    }

    /** Emoji.rects lookup with alias fallback, as in Emoji.getDrawableInfo. */
    fun resolveCode(code: CharSequence?): String? {
        if (code == null) return null
        var c: CharSequence = code
        if (endsWithRightArrow(c)) c = c.subSequence(0, c.length - 2)
        rects[c]?.let { return c.toString() }
        val alias = EmojiData.emojiAliasMap[c]
        if (alias != null && rects[alias] != null) return alias.toString()
        return null
    }

    fun itemInfo(code: CharSequence?): Info? {
        val resolved = resolveCode(code) ?: return null
        return rects[resolved]
    }

    fun canDraw(code: CharSequence?): Boolean = itemInfo(code) != null

    /** Longest drawable emoji starting at [start], or null. */
    fun match(text: String, start: Int): String? {
        val end = minOf(maxLen, text.length - start)
        for (len in end downTo 1) {
            val candidate = text.substring(start, start + len)
            if (drawableSet.contains(candidate)) return candidate
        }
        return null
    }

    fun hasVariants(code: String): Boolean = EmojiData.emojiColoredMap.contains(baseOf(code))

    /** Strips an existing skin-tone modifier (Telegram's code.replace(...) chain). */
    fun baseOf(code: String): String {
        var result = code
        for (tone in SKIN_TONES.drop(1)) result = result.replace(tone, "")
        return result
    }

    fun colorFor(code: String): String? = emojiColor[code]

    fun setColor(context: Context, base: String, tone: String?) {
        ensureLoaded(context)
        if (tone.isNullOrEmpty()) emojiColor.remove(base) else emojiColor[base] = tone
        _colors.value = HashMap(emojiColor)
        prefs(context).edit()
            .putString(KEY_COLORS, emojiColor.entries.joinToString(",") { "${it.key}=${it.value}" })
            .apply()
    }

    fun applyColor(code: String, tone: String?): String = if (tone.isNullOrEmpty()) code else code + tone

    /** Port of Emoji.fixEmoji (adds missing VS16 variation selectors). */
    fun fixEmoji(emoji: String): String {
        var value = emoji
        var a = 0
        var length = value.length
        while (a < length) {
            val ch = value[a]
            if (ch in '\uD83C'..'\uD83E') {
                if (ch == '\uD83C' && a < length - 1) {
                    val next = value[a + 1]
                    if (next == '\uDE2F' || next == '\uDC04' || next == '\uDE1A' || next == '\uDD7F') {
                        value = value.substring(0, a + 2) + "\uFE0F" + value.substring(a + 2)
                        length++
                        a += 2
                    } else {
                        a++
                    }
                } else {
                    a++
                }
            } else if (ch == '\u20E3') {
                return value
            } else if (ch in '\u203C'..'\u3299') {
                if (EmojiData.emojiToFE0FMap.containsKey(ch)) {
                    value = value.substring(0, a + 1) + "\uFE0F" + value.substring(a + 1)
                    length++
                    a++
                }
            }
            a++
        }
        return value
    }

    private fun endsWithRightArrow(code: CharSequence): Boolean =
        code.length > 2 && code[code.length - 2] == '\u200D' && code[code.length - 1] == '\u27A1'

    // --- recent emoji (Telegram Emoji.recentEmoji / emojiUseHistory) ---

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val store = prefs(context)
        val recents = store.getString(KEY_RECENT, null)
        if (recents.isNullOrEmpty()) {
            for (code in DEFAULT_RECENT) {
                emojiUseHistory[code] = 0
                recentEmoji.add(code)
            }
        } else {
            recents.split(',').filter { it.isNotEmpty() }.forEach { recentEmoji.add(it) }
            store.getString(KEY_COUNTS, null)?.split(',')?.forEach { entry ->
                val parts = entry.split('=')
                if (parts.size == 2) parts[1].toIntOrNull()?.let { count -> emojiUseHistory[parts[0]] = count }
            }
        }
        store.getString(KEY_COLORS, null)?.split(',')?.forEach { entry ->
            val parts = entry.split('=')
            if (parts.size == 2) emojiColor[parts[0]] = parts[1]
        }
        _recent.value = ArrayList(recentEmoji)
        _colors.value = HashMap(emojiColor)
    }

    fun addRecentEmoji(context: Context, code: String) {
        ensureLoaded(context)
        var count = emojiUseHistory[code] ?: 0
        if (count == 0 && emojiUseHistory.size >= MAX_RECENT) {
            val last = recentEmoji.lastOrNull()
            if (last != null) {
                emojiUseHistory.remove(last)
                recentEmoji[recentEmoji.size - 1] = code
            }
        }
        emojiUseHistory[code] = count + 1
        if (!recentEmoji.contains(code)) recentEmoji.add(0, code)
        sortEmoji()
        persist(context)
    }

    fun removeRecentEmoji(context: Context, code: String) {
        ensureLoaded(context)
        emojiUseHistory.remove(code)
        recentEmoji.remove(code)
        if (emojiUseHistory.isEmpty() || recentEmoji.isEmpty()) {
            addRecentEmoji(context, DEFAULT_RECENT[0])
        } else {
            sortEmoji()
            persist(context)
        }
    }

    fun clearRecentEmoji(context: Context) {
        ensureLoaded(context)
        emojiUseHistory.clear()
        recentEmoji.clear()
        persist(context)
    }

    private fun sortEmoji() {
        val sorted = recentEmoji.sortedByDescending { emojiUseHistory[it] ?: 0 }
        recentEmoji.clear()
        recentEmoji.addAll(sorted)
        while (recentEmoji.size > MAX_RECENT) recentEmoji.removeAt(recentEmoji.size - 1)
    }

    private fun persist(context: Context) {
        _recent.value = ArrayList(recentEmoji)
        prefs(context).edit()
            .putString(KEY_RECENT, recentEmoji.joinToString(","))
            .putString(KEY_COUNTS, emojiUseHistory.entries.joinToString(",") { "${it.key}=${it.value}" })
            .apply()
    }
}

/** Decoded emoji PNGs (64x64) with a bounded LRU cache. */
object EmojiImages {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = (value.width * value.height * 4) / 1024
    }

    private val FOLDERS = arrayOf("smileys", "nature", "food", "activity", "travel", "objects", "symbols", "flags")

    @Volatile
    private var assets: AssetManager? = null

    /** Enables loading emoji PNGs from assets/emoji/<category>/<codepoints>.png. */
    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    fun get(code: String): ImageBitmap? {
        cache.get(code)?.let { return it }
        val manager = assets ?: return null
        val resolved = TelegramEmoji.resolveCode(code) ?: return null
        val info = TelegramEmoji.itemInfo(resolved) ?: return null
        val codepoints = resolved.codePoints().toArray().joinToString("-") { Integer.toHexString(it) }
        val folder = FOLDERS.getOrElse(info.page) { "symbols" }
        val stream = runCatching { manager.open("emoji/$folder/$codepoints.png") }.getOrNull() ?: return null
        val bitmap = runCatching { BitmapFactory.decodeStream(stream) }.getOrNull()
        stream.close()
        if (bitmap == null) return null
        val image = bitmap.asImageBitmap()
        cache.put(code, image)
        return image
    }
}

/** Search index (char -> keywords) bundled as assets/emoji_names.json. */
object EmojiNames {
    @Volatile
    private var map: Map<String, String>? = null

    fun get(context: Context): Map<String, String> {
        map?.let { return it }
        return synchronized(this) {
            map ?: run {
                val loaded = runCatching {
                    val text = context.assets.open("emoji_names.json").bufferedReader().use { it.readText() }
                    val obj = JSONObject(text)
                    obj.keys().asSequence().associateWith { key -> obj.getString(key) }
                }.getOrDefault(emptyMap())
                map = loaded
                loaded
            }
        }
    }
}
