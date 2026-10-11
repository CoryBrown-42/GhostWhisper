package com.fieldkit.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fieldkit.app.data.EffectResult
import com.fieldkit.app.data.HearingProtection
import com.fieldkit.app.data.ObserverType
import com.fieldkit.app.data.RangeStop
import com.fieldkit.app.data.Session
import com.fieldkit.app.data.SessionInfo
import com.fieldkit.app.data.SessionStore
import com.fieldkit.app.data.Setting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Phenomenology Log form, kept here so it survives switching tabs mid-entry. */
data class ObservationDraft(
    val observerType: ObserverType = ObserverType.HUMAN,
    val species: String = "",
    val effects: Set<String> = emptySet(),
    val effectText: String = "",
    val severity: Int = 1,
    val duration: String = "",
    val setting: Setting = Setting.INDOOR,
    val weather: String = "",
    val equipment: String = "",
    val notes: String = "",
    val isBaseline: Boolean = false,
    // Control checklist
    val noAnimalsPresent: Boolean = false,
    val animalsSeparated: Boolean = false,
    val baselineTicked: Boolean = false,
    val protection: HearingProtection? = null,
)

/** Shielding Test wizard progress. */
data class ShieldingDraft(
    val step: Int = 0,
    val startedMs: Long = 0L,
    val baselinePresent: Boolean? = null,
    val baselineNotes: String = "",
    val faradayResult: EffectResult? = null,
    val faradayNotes: String = "",
    val faradayTimeMs: Long = 0L,
    val returned: Boolean? = null,
    val protectionResult: EffectResult? = null,
    val protectionNotes: String = "",
    val protectionTimeMs: Long = 0L,
)

/** Range Map transect in progress. Start coordinates live only here, in memory. */
data class TransectDraft(
    val active: Boolean = false,
    val startedMs: Long = 0L,
    val incrementText: String = "5",
    val startNote: String = "",
    val useGps: Boolean = false,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val stops: List<RangeStop> = emptyList(),
) {
    val incrementM: Double get() = incrementText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: 5.0
}

/**
 * Holds the open session and writes every change to disk. Screens change the
 * session with [update]; they never touch files themselves.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = SessionStore(app)

    var session by mutableStateOf(loadInitialSession())
        private set

    var sessions by mutableStateOf<List<SessionInfo>>(emptyList())
        private set

    var darkTheme by mutableStateOf(store.darkTheme)
        private set

    var observationDraft by mutableStateOf(ObservationDraft())
    var shieldingDraft by mutableStateOf(ShieldingDraft())
    var transectDraft by mutableStateOf(TransectDraft())

    // Conflated: if changes arrive faster than the disk, only the newest is written.
    private val saveQueue = Channel<Session>(Channel.CONFLATED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (pending in saveQueue) {
                try {
                    store.save(pending)
                } catch (e: Exception) {
                    // Disk full or similar: the session stays in memory and the next change retries.
                }
            }
        }
    }

    private fun loadInitialSession(): Session {
        val last = store.lastSessionId?.let { store.load(it) }
        val session = last ?: store.create()
        store.lastSessionId = session.id
        return session
    }

    /** Replace the open session with a modified copy and queue it for saving. */
    fun update(change: (Session) -> Session) {
        session = change(session)
        saveQueue.trySend(session)
    }

    fun changeTheme(dark: Boolean) {
        darkTheme = dark
        store.darkTheme = dark
    }

    fun refreshSessions() {
        viewModelScope.launch {
            sessions = withContext(Dispatchers.IO) {
                saveNow()
                store.list()
            }
        }
    }

    fun newSession(name: String) {
        saveNow()
        open(store.create(name))
    }

    fun switchTo(id: String) {
        if (id == session.id) return
        saveNow()
        store.load(id)?.let { open(it) }
    }

    fun deleteSession(id: String) {
        if (id == session.id) {
            // Deleting the open session: move to another one, or a fresh one.
            store.delete(id)
            val next = store.list().firstOrNull()?.let { store.load(it.id) } ?: store.create()
            open(next)
        } else {
            store.delete(id)
            refreshSessions()
        }
    }

    private fun open(next: Session) {
        session = next
        store.lastSessionId = next.id
        observationDraft = ObservationDraft()
        shieldingDraft = ShieldingDraft()
        transectDraft = TransectDraft()
        refreshSessions()
    }

    /** Write the open session immediately (used before switching away from it). */
    private fun saveNow() {
        try {
            store.save(session)
        } catch (e: Exception) {
            // see saveQueue consumer
        }
    }

    override fun onCleared() {
        saveNow()
        saveQueue.close()
    }
}
