package com.jainam.studentos.focusshield.feature.scanner

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jainam.studentos.focusshield.core.common.ErrorHandler
import com.jainam.studentos.focusshield.data.AppCategory
import com.jainam.studentos.focusshield.data.AppDatabase
import com.jainam.studentos.focusshield.data.CategoryType
import com.jainam.studentos.focusshield.data.LogicalState
import com.jainam.studentos.focusshield.data.toLogicalState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PackageScannerViewModel(
    private val database: AppDatabase,
    private val context: Context
) : ViewModel() {

    private val scanner = PackageScanner(context)

    private val _scannedApps = MutableStateFlow<List<ScannedApp>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _selectedFilter = MutableStateFlow<CategoryFilter>(CategoryFilter.All)
    val selectedFilter: StateFlow<CategoryFilter> = _selectedFilter.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val uiState: StateFlow<List<ScannedApp>> = combine(
        _scannedApps,
        _searchQuery,
        _selectedFilter
    ) { apps, query, filter ->
        var resultList = apps

        if (query.isNotBlank()) {
            val lowerQuery = query.lowercase()
            resultList = resultList.filter {
                it.appName.lowercase().contains(lowerQuery) ||
                        it.packageName.lowercase().contains(lowerQuery)
            }
        }

        resultList = when (filter) {
            is CategoryFilter.All -> resultList
            is CategoryFilter.Allowed -> resultList.filter {
                // Everything the engine will not block, including the default
                // UNCATEGORIZED state. Restricting this to USER_ALLOWED/STUDY
                // made the "Allowed" tab almost always empty because scanning
                // classifies untouched apps as UNCATEGORIZED.
                it.categoryType == CategoryType.ESSENTIAL ||
                        it.categoryType.toLogicalState() == LogicalState.ALLOWED
            }
            is CategoryFilter.Blocked -> resultList.filter {
                // Only apps that are ACTUALLY blocked. This tab used to list every
                // app that was not on the allowlist (≈ every installed app), so
                // users believed WhatsApp/Chrome were blocked while the blocklist
                // engine was allowing them. A tab called "Blocked" must show the
                // blocklist, nothing else.
                it.categoryType == CategoryType.BLOCKED ||
                        it.categoryType == CategoryType.ALWAYS_BLOCKED ||
                        it.categoryType == CategoryType.BREAK_BLOCKED
            }
        }

        resultList.sortedBy { it.appName.lowercase() }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        performAppScan()
    }

    fun performAppScan(forceRefresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            ErrorHandler.runCatchingSafe(
                fallbackValue = emptyList(),
                onException = { _isLoading.value = false }
            ) {
                val apps = scanner.scanInstalledApps(forceRefresh)
                val roomCategories = database.appCategoryDao().getAllCategories().associateBy { it.packageName }

                val syncedApps = apps.map { app ->
                    val roomApp = roomCategories[app.packageName]
                    val isTrulyEssential = scanner.classifyApp(app.packageName, app.appName, app.isSystemApp) == CategoryType.ESSENTIAL
                    if (roomApp != null) {
                        if (roomApp.categoryType == CategoryType.ESSENTIAL && !isTrulyEssential) {
                            val correctedApp = app.copy(categoryType = CategoryType.BLOCKED)
                            database.appCategoryDao().insertCategory(
                                AppCategory(
                                    packageName = app.packageName,
                                    appName = app.appName,
                                    categoryType = CategoryType.BLOCKED,
                                    isSystemApp = app.isSystemApp
                                )
                            )
                            correctedApp
                        } else {
                            app.copy(categoryType = roomApp.categoryType)
                        }
                    } else {
                        database.appCategoryDao().insertCategory(
                            AppCategory(
                                packageName = app.packageName,
                                appName = app.appName,
                                categoryType = app.categoryType,
                                isSystemApp = app.isSystemApp
                            )
                        )
                        app
                    }
                }

                _scannedApps.value = syncedApps
                _isLoading.value = false
                syncedApps
            }
        }
    }

    /**
     * Block-list semantics: the switch in "Manage Apps" answers the question
     * "is this app blocked?", not "is this app allowed?". Default state is
     * UNCATEGORIZED (allowed) so nothing is restricted until the user opts in.
     */
    fun toggleAppBlocked(app: ScannedApp, blocked: Boolean) {
        when (app.categoryType) {
            // Essential apps are protected from blocking.
            CategoryType.ESSENTIAL -> return
            // The user's 24/7 / break lists live in the same table and share the
            // packageName primary key. Writing BLOCKED here used to REPLACE the
            // stronger block row, so tapping a "24/7" app in this screen silently
            // downgraded it. Those lists are owned by the Deep Lock screen.
            CategoryType.ALWAYS_BLOCKED, CategoryType.BREAK_BLOCKED -> return
            else -> Unit
        }
        val newCategory = if (blocked) CategoryType.BLOCKED else CategoryType.UNCATEGORIZED
        updateAppCategory(app.packageName, app.appName, newCategory, app.isSystemApp)
    }

    fun updateAppCategory(packageName: String, appName: String, categoryType: CategoryType, isSystem: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            ErrorHandler.runCatchingSafe(Unit) {
                database.appCategoryDao().insertCategory(
                    AppCategory(packageName, appName, categoryType, isSystem)
                )
                _scannedApps.update { currentList ->
                    currentList.map { app ->
                        if (app.packageName == packageName) {
                            app.copy(categoryType = categoryType)
                        } else {
                            app
                        }
                    }
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: CategoryFilter) {
        _selectedFilter.value = filter
    }
}

sealed interface CategoryFilter {
    object Allowed : CategoryFilter
    object Blocked : CategoryFilter
    object All : CategoryFilter
}

