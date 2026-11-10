package dev.arya.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.arya.messenger.emoji.EmojiImages
import dev.arya.messenger.ui.AryaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmojiImages.init(this)
        val app = application as AryaApplication
        setContent { AryaApp(app.repository, app.network, app.config) }
    }
}
