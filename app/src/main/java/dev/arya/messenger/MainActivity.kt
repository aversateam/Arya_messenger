package dev.arya.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.arya.messenger.data.AppRepository
import dev.arya.messenger.data.LanPeerTransport
import dev.arya.messenger.emoji.EmojiImages
import dev.arya.messenger.ui.AryaApp
import dev.arya.messenger.ui.repoScope

class MainActivity : ComponentActivity() {
    private lateinit var repository: AppRepository
    private lateinit var lan: LanPeerTransport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmojiImages.init(this)
        repository = AppRepository(this)
        lan = LanPeerTransport(
            localIdentity = { repository.getLocalIdentity() },
            isTrustedPeer = { publicKey -> repository.isVerifiedPeer(publicKey) },
            onMessage = { peerKey, peerName, body -> repository.receiveLanMessage(peerKey, peerName, body) }
        )
        lan.start()
        setContent { AryaApp(repository, lan) }
    }

    override fun onDestroy() {
        lan.close()
        super.onDestroy()
    }
}
