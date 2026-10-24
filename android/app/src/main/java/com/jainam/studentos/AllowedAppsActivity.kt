package com.jainam.studentos

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jainam.studentos.focusshield.data.AppDatabase
import com.jainam.studentos.focusshield.data.CategoryType
import com.jainam.studentos.focusshield.feature.scanner.CategoryFilter
import com.jainam.studentos.focusshield.feature.scanner.PackageScannerViewModel
import com.jainam.studentos.focusshield.feature.scanner.ScannedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AllowedAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("[AllowedApps]", "onCreate() — AllowedAppsActivity launched")
        setContent {
            AppTheme {
                AllowedAppsScreen(
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            surface = Color(0xFF1a1a2e),
            background = Color(0xFF0f0f1a),
            primary = Color(0xFF7c5cbf),
            secondary = Color(0xFF5c8abf),
            onSurface = Color(0xFFe8e6f0),
            onBackground = Color(0xFFe8e6f0),
            surfaceVariant = Color(0xFF252538),
            onSurfaceVariant = Color(0xFFb0aec0),
            outline = Color(0xFF3a3a4a),
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllowedAppsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val viewModel: PackageScannerViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return PackageScannerViewModel(db, context) as T
            }
        }
    )

    val apps by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Apps") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search apps...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                )
            )

            ManageTabsRow(
                selectedFilter = selectedFilter,
                onTabSelected = { viewModel.setFilter(it) }
            )

            Text(
                text = "Turn on Block to lock an app during focus sessions. Apps you leave off keep working normally. Essential apps can't be blocked.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (apps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedFilter is CategoryFilter.Blocked)
                            "No apps blocked yet.\nPick one from the All tab."
                        else "No apps match this filter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            onToggle = { blocked -> viewModel.toggleAppBlocked(app, blocked) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageTabsRow(
    selectedFilter: CategoryFilter,
    onTabSelected: (CategoryFilter) -> Unit
) {
    val tabs = listOf(
        CategoryFilter.Allowed to "Allowed",
        CategoryFilter.Blocked to "Blocked",
        CategoryFilter.All to "All"
    )

    val selectedIndex = when (selectedFilter) {
        CategoryFilter.Allowed -> 0
        CategoryFilter.Blocked -> 1
        CategoryFilter.All -> 2
    }

    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        tabs.forEachIndexed { index, (filter, label) ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onTabSelected(filter) },
                text = {
                    Text(
                        text = label,
                        fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            )
        }
    }
}

@Composable
private fun AppRow(
    app: ScannedApp,
    onToggle: (Boolean) -> Unit
) {
    val isEssential = app.categoryType == CategoryType.ESSENTIAL
    val isAlwaysBlocked = app.categoryType == CategoryType.ALWAYS_BLOCKED ||
        app.categoryType == CategoryType.BREAK_BLOCKED
    val isExplicitlyBlocked = app.categoryType == CategoryType.BLOCKED
    // The switch asks "is this app blocked?" Default (UNCATEGORIZED) = off.
    val isBlocked = isExplicitlyBlocked || isAlwaysBlocked
    // 24/7 and break lists are owned by the Deep Lock screen; flipping them here
    // would replace the block row (shared packageName PK) and weaken the block.
    // BLOCKED (session-only) stays toggleable: this screen is where a user
    // expects to lock an app "during focus sessions".
    val toggleEnabled = !isEssential && !isAlwaysBlocked

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = toggleEnabled) { onToggle(!isBlocked) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppIcon(packageName = app.packageName, appName = app.appName)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            when {
                isEssential -> StatusBadge("Essential", Color(0xFF4CAF50))
                isAlwaysBlocked -> StatusBadge("24/7", Color(0xFFFFB74D))
                isExplicitlyBlocked -> StatusBadge("Session", Color(0xFF4DD0E1))
            }

            if (!isEssential) {
                Switch(
                    checked = isBlocked,
                    enabled = toggleEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AppIcon(packageName: String, appName: String) {
    val context = LocalContext.current
    var iconDrawable by remember(packageName) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                iconDrawable = context.packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
                iconDrawable = null
            }
        }
    }

    val bitmap = remember(iconDrawable) {
        try {
            iconDrawable?.toBitmap()?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = appName,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = appName.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}
