package dev.arya.messenger.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arya.messenger.data.AppRepository
import dev.arya.messenger.data.NetworkConfig
import dev.arya.messenger.network.NetworkRuntime
import dev.arya.messenger.security.IdentityKeys
import dev.arya.messenger.ui.screens.ContactsScreen
import dev.arya.messenger.ui.screens.ConversationScreen
import dev.arya.messenger.ui.screens.HomeScreen
import dev.arya.messenger.ui.screens.LanScreen
import dev.arya.messenger.ui.screens.OnboardingScreen
import dev.arya.messenger.ui.theme.AryaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

object Routes {
    const val HOME = "home"
    const val CONTACTS = "contacts"
    const val LAN = "lan"
    const val CONVERSATION = "conversation/{conversationId}"
    fun conversation(id: String) = "conversation/$id"
}

internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

internal fun repoScope(block: suspend () -> Unit) {
    appScope.launch { runCatching { block() } }
}

@Composable
fun AryaApp(repo: AppRepository, network: NetworkRuntime, config: NetworkConfig) {
    val identity by repo.observe().collectAsStateWithLifecycle(initialValue = null)

    AryaTheme {
        // Surface paints the theme background AND provides the matching content color,
        // so every page (including bare Column screens and colorless Text) adapts
        // correctly between light and dark modes.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (identity == null) {
                OnboardingScreen { name ->
                    val alias = "arya-identity-${UUID.randomUUID()}"
                    val public = IdentityKeys.create(alias)
                    repoScope { repo.create(name, public.encodedPublicKey, public.alias) }
                }
            } else {
                val navController = rememberNavController()
                // LAN advertising and the listening socket now belong to AryaApplication, so they
                // survive recomposition, rotation, and backgrounding instead of following the UI.
                NavHost(navController = navController, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        HomeScreen(repo, identity!!, config, navController)
                    }
                    composable(Routes.CONTACTS) {
                        ContactsScreen(repo, config, navController)
                    }
                    composable(Routes.CONVERSATION) { entry ->
                        val id = entry.arguments?.getString("conversationId") ?: return@composable
                        ConversationScreen(repo, id, navController)
                    }
                    composable(Routes.LAN) {
                        LanScreen(repo, network, config, navController)
                    }
                }
            }
        }
    }
}
