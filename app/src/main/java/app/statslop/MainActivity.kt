package app.statslop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.statslop.ui.AppsScreen
import app.statslop.ui.BatteryScreen
import app.statslop.ui.SystemScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SampleWorker.schedule(this)
        setContent {
            val context = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = scheme) { StatSlopApp() }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Battery("Battery", Icons.Outlined.BatteryChargingFull),
    System("System", Icons.Outlined.Memory),
    Apps("Apps", Icons.Outlined.Apps),
}

@Composable
private fun StatSlopApp() {
    val context = LocalContext.current
    val db = remember { HistoryDb.get(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var tab by rememberSaveable { mutableStateOf(Tab.Battery) }
    var battery by remember { mutableStateOf<BatterySnapshot?>(null) }
    var system by remember { mutableStateOf<SystemSnapshot?>(null) }
    var historyDays by rememberSaveable { mutableIntStateOf(1) }
    var history by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var drain by remember { mutableStateOf(DrainStats(null, 0f, null, null)) }
    var usageDays by rememberSaveable { mutableIntStateOf(0) }
    var usagePermission by remember { mutableStateOf(UsageReader.hasPermission(context)) }
    var usage by remember { mutableStateOf<UsageSummary?>(null) }

    // Live readings every 2 s while visible; a history sample at most once a minute.
    LaunchedEffect(lifecycle, historyDays) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastRecorded = 0L
            while (true) {
                withContext(Dispatchers.IO) {
                    val b = BatteryReader.read(context)
                    battery = b
                    system = SystemReader.read(context)
                    if (b != null && b.timestamp - lastRecorded >= 60_000) {
                        lastRecorded = b.timestamp
                        db.record(b)
                        val from = b.timestamp - historyDays * 86_400_000L
                        history = db.since(from)
                        val lastPlugged = db.lastPluggedTs()
                        drain = drainStats(db.since(b.timestamp - 7 * 86_400_000L), b, lastPlugged, lastPlugged?.let(db::levelAt))
                    }
                }
                delay(2_000)
            }
        }
    }

    LaunchedEffect(lifecycle, usageDays) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            usagePermission = UsageReader.hasPermission(context)
            if (usagePermission) {
                usage = null
                usage = withContext(Dispatchers.IO) { UsageReader.read(context, usageDays) }
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = { Icon(it.icon, null) },
                        label = { Text(it.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            when (tab) {
                Tab.Battery -> BatteryScreen(battery, history, historyDays, { historyDays = it }, drain)
                Tab.System -> SystemScreen(system)
                Tab.Apps -> AppsScreen(usagePermission, usage, usageDays, { usageDays = it })
            }
        }
    }
}
