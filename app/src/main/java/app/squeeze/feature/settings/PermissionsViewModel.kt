package app.squeeze.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AppPermissions.snapshot(context))
    val state: StateFlow<Map<AppPermission, Boolean>> = _state

    /** Call after a dialog or when the screen comes back from a system settings page. */
    fun refresh() {
        _state.update { AppPermissions.snapshot(context) }
    }

    /** Marks the first-run screen as seen so it is not shown again, whatever was granted. */
    fun finishOnboarding(then: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingDone()
            then()
        }
    }
}
