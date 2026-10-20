package app.squeeze.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.engine.CodecGroup
import app.squeeze.engine.DeviceCodecProbe
import app.squeeze.engine.groupEncoders
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class CapabilitiesViewModel @Inject constructor(
    private val probe: DeviceCodecProbe,
) : ViewModel() {
    private val _groups = MutableStateFlow<List<CodecGroup>?>(null)

    /** Null while the device is being asked. */
    val groups: StateFlow<List<CodecGroup>?> = _groups

    init {
        viewModelScope.launch {
            _groups.value = withContext(Dispatchers.Default) { groupEncoders(probe.encoders()) }
        }
    }
}
