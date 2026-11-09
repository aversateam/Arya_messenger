package dev.arya.messenger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.arya.messenger.emoji.EmojiImages
import dev.arya.messenger.emoji.TelegramEmoji

/**
 * Text renderer that replaces emoji with the images from Telegram's emoji.pack,
 * inline at the baseline — identical rendering for the composer preview and
 * received messages.
 */
@Composable
fun EmojiText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    fontSize: TextUnit = TextStyle.Default.fontSize,
    color: Color = Color.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val effectiveFontSize = style.fontSize.takeIf { it != TextUnit.Unspecified } ?: fontSize
    val mergedStyle = style.copy(
        fontSize = effectiveFontSize,
        color = color,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        textAlign = textAlign ?: TextAlign.Unspecified,
        textDecoration = textDecoration
    )
    val emojiSize = effectiveFontSize * 1.25f
    val emojiDp = with(LocalDensity.current) { emojiSize.toPx().toDp() }
    val (annotated, inlineContent) = remember(text, emojiSize, emojiDp) {
        buildRichText(text, emojiSize, emojiDp)
    }
    BasicText(
        text = annotated,
        modifier = modifier,
        style = mergedStyle,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent
    )
}

private fun buildRichText(
    text: String,
    emojiSize: TextUnit,
    emojiDp: Dp
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    if (text.isEmpty()) return AnnotatedString(text) to emptyMap()
    val content = mutableMapOf<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val code = TelegramEmoji.match(text, i)
            if (code != null) {
                val id = "e:" + code
                appendInlineContent(id, code)
                content.getOrPut(id) {
                    InlineTextContent(
                        Placeholder(
                            width = emojiSize,
                            height = emojiSize,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline
                        )
                    ) { _ ->
                        val bitmap = EmojiImages.get(code)
                        if (bitmap != null) {
                            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(emojiDp))
                        }
                    }
                }
                i += code.length
            } else {
                append(text.substring(i, i + 1))
                i += 1
            }
        }
    }
    return annotated to content
}
