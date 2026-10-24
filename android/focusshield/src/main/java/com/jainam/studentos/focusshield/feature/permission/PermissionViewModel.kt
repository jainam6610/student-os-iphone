package com.jainam.studentos.focusshield.feature.permission

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jainam.studentos.focusshield.core.utils.Logger
import com.jainam.studentos.focusshield.data.datastore.FocusPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PermissionItemState(
    val id: String,
    val title: String,
    val description: String,
    val status: PermissionStatus,
    val isRequired: Boolean
)

enum class PermissionStatus {
    GRANTED,
    MISSING,
    RECOMMENDED
}

class PermissionViewModel(
    private val context: Context,
    private val focusPreferences: FocusPreferences
) : ViewModel() {

    private val permissionManager = PermissionManager(context)

    private val _uiState = MutableStateFlow(PermissionDashboardState())
    val uiState: StateFlow<PermissionDashboardState> = _uiState.asStateFlow()

    init {
        checkPermissions()
    }

    fun checkPermissions() {
        viewModelScope.launch(Dispatchers.IO) {
            val accessibilityGranted = permissionManager.isAccessibilityServiceEnabled()
            val notificationsGranted = permissionManager.isNotificationPermissionGranted()
            val batteryOptimizationDisabled = permissionManager.isBatteryOptimizationDisabled()
            val dndGranted = permissionManager.isDndPolicyAccessGranted()

            _uiState.update { currentState ->
                currentState.copy(
                    accessibilityStatus = if (accessibilityGranted) PermissionStatus.GRANTED else PermissionStatus.MISSING,
                    notificationsStatus = if (notificationsGranted) PermissionStatus.GRANTED else PermissionStatus.MISSING,
                    foregroundServiceStatus = PermissionStatus.GRANTED,
                    batteryOptimizationStatus = if (batteryOptimizationDisabled) PermissionStatus.GRANTED else PermissionStatus.RECOMMENDED,
                    dndStatus = if (dndGranted) PermissionStatus.GRANTED else PermissionStatus.RECOMMENDED,
                    lastRefreshTimestamp = System.currentTimeMillis()
                )
            }
            Logger.i("FocusShield_PermissionVM", "State updated with system permissions check.")
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch(Dispatchers.IO) {
            focusPreferences.setOnboardingCompleted(true)
            Logger.i("FocusShield_PermissionVM", "User completed first-run tutorial. State persisted.")
        }
    }
}

data class PermissionDashboardState(
    val accessibilityStatus: PermissionStatus = PermissionStatus.MISSING,
    val notificationsStatus: PermissionStatus = PermissionStatus.MISSING,
    val foregroundServiceStatus: PermissionStatus = PermissionStatus.GRANTED,
    val batteryOptimizationStatus: PermissionStatus = PermissionStatus.RECOMMENDED,
    val dndStatus: PermissionStatus = PermissionStatus.RECOMMENDED,
    val lastRefreshTimestamp: Long = 0L
)
