package org.pocketlab.puppy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How remote programs may write the system clipboard (OSC 52). */
enum class ClipboardPolicy { ASK, ALLOW, DENY }

data class Settings(
    val fontSizeSp: Float = 13f,
    val scrollbackLines: Int = 10_000,
    val themeId: String? = null,
    /** Derive app bar and sidebar colors from the terminal theme. */
    val uiFollowsTerminal: Boolean = true,
    val confirmMultilinePaste: Boolean = true,
    val clipboardWrite: ClipboardPolicy = ClipboardPolicy.ASK,
    val keepScreenOn: Boolean = false,
    val bell: Boolean = true,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val fontSize = floatPreferencesKey("font_size_sp")
        val scrollback = intPreferencesKey("scrollback_lines")
        val theme = stringPreferencesKey("theme_id")
        val uiFollows = booleanPreferencesKey("ui_follows_terminal")
        val confirmPaste = booleanPreferencesKey("confirm_multiline_paste")
        val clipboard = stringPreferencesKey("clipboard_write")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val bell = booleanPreferencesKey("bell")
    }

    val settings: Flow<Settings> = store.data.map { p ->
        val d = Settings()
        Settings(
            fontSizeSp = p[Keys.fontSize] ?: d.fontSizeSp,
            scrollbackLines = p[Keys.scrollback] ?: d.scrollbackLines,
            themeId = p[Keys.theme],
            uiFollowsTerminal = p[Keys.uiFollows] ?: d.uiFollowsTerminal,
            confirmMultilinePaste = p[Keys.confirmPaste] ?: d.confirmMultilinePaste,
            clipboardWrite = p[Keys.clipboard]?.let { runCatching { ClipboardPolicy.valueOf(it) }.getOrNull() } ?: d.clipboardWrite,
            keepScreenOn = p[Keys.keepScreenOn] ?: d.keepScreenOn,
            bell = p[Keys.bell] ?: d.bell,
        )
    }

    suspend fun setFontSize(sp: Float) = store.edit { it[Keys.fontSize] = sp }
    suspend fun setScrollback(lines: Int) = store.edit { it[Keys.scrollback] = lines.coerceIn(0, 200_000) }
    suspend fun setTheme(id: String) = store.edit { it[Keys.theme] = id }
    suspend fun setUiFollowsTerminal(v: Boolean) = store.edit { it[Keys.uiFollows] = v }
    suspend fun setConfirmMultilinePaste(v: Boolean) = store.edit { it[Keys.confirmPaste] = v }
    suspend fun setClipboardPolicy(v: ClipboardPolicy) = store.edit { it[Keys.clipboard] = v.name }
    suspend fun setKeepScreenOn(v: Boolean) = store.edit { it[Keys.keepScreenOn] = v }
    suspend fun setBell(v: Boolean) = store.edit { it[Keys.bell] = v }
}
