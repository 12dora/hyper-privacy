package io.github.hyperprivacy.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import io.github.hyperprivacy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class DarkMode(@param:StringRes val label: Int) {
    SYSTEM(R.string.appearance_mode_system),
    LIGHT(R.string.appearance_mode_light),
    DARK(R.string.appearance_mode_dark),
}

/** How the app looks. A UI preference only: it never reaches the hooks. */
@Immutable
internal data class Appearance(
    val mode: DarkMode = DarkMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.DYNAMIC,
    val pureBlack: Boolean = false,
)

/**
 * App-local persistence for [Appearance]. Loaded once off the main thread at process start; until
 * then [appearance] is `null` and the UI waits a frame rather than flashing the wrong theme.
 */
internal object AppearanceStore {
    private const val FILE_NAME = "appearance"
    private const val KEY_MODE = "mode"
    private const val KEY_PALETTE = "palette"
    private const val KEY_PURE_BLACK = "pure_black"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutable = MutableStateFlow<Appearance?>(null)
    private var prefs: SharedPreferences? = null

    val appearance: StateFlow<Appearance?> = mutable.asStateFlow()

    /** Must be called once, from [android.app.Application.onCreate]. */
    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val loaded = app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            prefs = loaded
            mutable.compareAndSet(null, read(loaded))
        }
    }

    fun update(transform: (Appearance) -> Appearance) {
        val next = transform(mutable.value ?: Appearance())
        mutable.value = next
        scope.launch {
            prefs?.edit {
                putString(KEY_MODE, next.mode.name)
                putString(KEY_PALETTE, next.palette.name)
                putBoolean(KEY_PURE_BLACK, next.pureBlack)
            }
        }
    }

    private fun read(prefs: SharedPreferences) = Appearance(
        mode = DarkMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: DarkMode.SYSTEM,
        palette = ThemePalette.entries.firstOrNull { it.name == prefs.getString(KEY_PALETTE, null) }
            ?: ThemePalette.DYNAMIC,
        pureBlack = prefs.getBoolean(KEY_PURE_BLACK, false),
    )
}
