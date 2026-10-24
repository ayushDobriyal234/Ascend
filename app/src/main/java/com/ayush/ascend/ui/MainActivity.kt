package com.ayush.ascend.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ayush.ascend.core.EditResult
import com.ayush.ascend.core.graph
import com.ayush.ascend.ui.screens.FirewallScreen
import com.ayush.ascend.ui.screens.HomeScreen
import com.ayush.ascend.ui.screens.InboxScreen
import com.ayush.ascend.ui.screens.InsightsScreen
import com.ayush.ascend.ui.screens.LimitsScreen
import com.ayush.ascend.ui.screens.PartnerScreen
import com.ayush.ascend.ui.screens.ReportScreen
import com.ayush.ascend.ui.screens.SetupScreen
import com.ayush.ascend.ui.screens.ShieldScreen
import com.ayush.ascend.ui.screens.DailyChestPrompt
import com.ayush.ascend.ui.screens.ShopScreen
import com.ayush.ascend.ui.theme.AscendTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

class MainActivity : ComponentActivity() {

    private val startRoute = mutableStateOf<String?>(null)
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        startRoute.value = intent.getStringExtra(EXTRA_ROUTE)
        setContent { AscendTheme { AscendRoot(startRoute.value, resumes.intValue) { startRoute.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startRoute.value = intent.getStringExtra(EXTRA_ROUTE)
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        lifecycleScope.launch { graph.policy.applyDue(); graph.usage.invalidate() }
    }

    companion object { const val EXTRA_ROUTE = "route" }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Summit", Icons.Outlined.Landscape),
    Tab("limits", "Limits", Icons.Outlined.Timer),
    Tab("shield", "Shield", Icons.Outlined.Shield),
    Tab("insights", "Insights", Icons.Outlined.Insights),
    Tab("shop", "Shop", Icons.Outlined.Storefront),
)

@Composable
private fun AscendRoot(deepLink: String?, resumes: Int, consumed: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings by ctx.graph.prefs.settings.collectAsStateWithLifecycle()

    LaunchedEffect(deepLink) { deepLink?.let { nav.navigate(it); consumed() } }
    LaunchedEffect(settings.features.isNotEmpty(), settings.onboarded) {
        if (settings.features.isNotEmpty() && !settings.onboarded) nav.navigate("setup")
    }
    var showChest by remember { mutableStateOf(false) }
    LaunchedEffect(resumes, settings.onboarded) { if (settings.onboarded) showChest = ctx.graph.wallet.chestReady() }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { BottomBar(nav) },
        ) { pad ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
                composable("home") { HomeScreen(nav) }
                composable("limits") { LimitsScreen() }
                composable("shield") { ShieldScreen(nav) }
                composable("insights") { InsightsScreen(nav) }
                composable("report?week={week}", listOf(androidx.navigation.navArgument("week") { nullable = true; defaultValue = null })) {
                    ReportScreen(nav, it.arguments?.getString("week"))
                }
                composable("setup") { SetupScreen(nav) }
                composable("inbox") { InboxScreen(nav) }
                composable("firewall") { FirewallScreen(nav) }
                composable("partner") { PartnerScreen(nav) }
                composable("shop") { ShopScreen() }
            }
            if (showChest) DailyChestPrompt { showChest = false }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    if (tabs.none { it.route == current }) return
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        tabs.forEach { t ->
            NavigationBarItem(
                selected = current == t.route,
                onClick = { nav.navigate(t.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } },
                icon = { Icon(t.icon, t.label) },
                label = { Text(t.label) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
            )
        }
    }
}

/** Human text for a policy outcome, shown in a snackbar. */
fun EditResult.message(): String? = when (this) {
    EditResult.Applied -> null
    is EditResult.Scheduled -> "Turning off in 24 h (${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(applyAt))}). You can cancel it any time."
    is EditResult.Locked -> "Locked for $daysLeft more days. You can make it stricter, not looser."
    EditResult.NeedsNetwork -> "Connect to the internet so Ascend can verify today's date."
    is EditResult.Invalid -> message
}
