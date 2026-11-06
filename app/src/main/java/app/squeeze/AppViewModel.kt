package app.squeeze

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.data.SettingsRepository
import app.squeeze.data.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the activity needs before it can draw anything: theme and whether to start with the permissions screen. */
data class AppUi(val themeMode: ThemeMode, val dynamicColor: Boolean, val onboardingDone: Boolean)

@HiltViewModel
class AppViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    /** Null until the settings are read, so the first frame is not drawn with the wrong theme or start screen. */
    val ui: StateFlow<AppUi?> = combine(settings.themeMode, settings.dynamicColor, settings.onboardingDone, ::AppUi)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _notificationTarget = MutableStateFlow<Long?>(null)

    /** The batch a result-notification tap wants History to focus on, until HistoryScreen consumes it. */
    val notificationTarget: StateFlow<Long?> = _notificationTarget.asStateFlow()

    fun openFromNotification(batchId: Long) {
        _notificationTarget.value = batchId
    }

    fun clearNotificationTarget() {
        _notificationTarget.value = null
    }
}
