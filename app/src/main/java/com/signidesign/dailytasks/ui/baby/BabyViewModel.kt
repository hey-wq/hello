package com.signidesign.dailytasks.ui.baby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.signidesign.dailytasks.TaskApp
import com.signidesign.dailytasks.data.BabyEventEntity
import com.signidesign.dailytasks.data.BabyEventKind
import com.signidesign.dailytasks.data.BabyLane
import com.signidesign.dailytasks.data.BabyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Baby tracking gets its own ViewModel rather than joining `AppViewModel`.
 * The task screens are projections over one pair of repositories; this is a
 * second domain with its own write rules (open sessions, lanes), and
 * folding it in would make the shared surface the thing nobody can read.
 */
class BabyViewModel(private val baby: BabyRepository) : ViewModel() {

    val zone: ZoneId = ZoneId.systemDefault()

    /** Last thing logged, for a one-tap undo. */
    private val _lastLogged = MutableStateFlow<BabyEventEntity?>(null)
    val lastLogged: StateFlow<BabyEventEntity?> = _lastLogged.asStateFlow()

    val openFeed: Flow<BabyEventEntity?> = baby.openFeed()
    val openSleep: Flow<BabyEventEntity?> = baby.openSleep()

    fun eventsFor(day: LocalDate): Flow<List<BabyEventEntity>> =
        baby.eventsForDay(day, zone)

    // --------------------------------------------------------------- sessions

    /**
     * Tap a breast: starts that side, or ends it if that exact side is
     * already running (a second tap means "done"), or switches sides when
     * the other one is.
     */
    fun tapBreast(kind: BabyEventKind) = viewModelScope.launch {
        val open = baby.openFeedOnce()
        if (open != null && open.kind == kind) {
            _lastLogged.value = baby.endSession(BabyLane.FEED)
        } else {
            val id = baby.startSession(kind)
            _lastLogged.value = baby.byId(id)
        }
    }

    fun tapSleep() = viewModelScope.launch {
        val open = baby.openSleepOnce()
        if (open != null) {
            _lastLogged.value = baby.endSession(BabyLane.SLEEP)
        } else {
            val id = baby.startSession(BabyEventKind.SLEEP)
            _lastLogged.value = baby.byId(id)
        }
    }

    fun tapPump() = viewModelScope.launch {
        val open = baby.openFeedOnce()
        if (open != null && open.kind == BabyEventKind.PUMP) {
            _lastLogged.value = baby.endSession(BabyLane.FEED)
        } else {
            val id = baby.startSession(BabyEventKind.PUMP)
            _lastLogged.value = baby.byId(id)
        }
    }

    fun endFeed() = viewModelScope.launch {
        _lastLogged.value = baby.endSession(BabyLane.FEED)
    }

    fun endSleep() = viewModelScope.launch {
        _lastLogged.value = baby.endSession(BabyLane.SLEEP)
    }

    // ----------------------------------------------------------------- events

    fun logBottle(amountMl: Int?) = viewModelScope.launch {
        val id = baby.logEvent(BabyEventKind.BOTTLE, amountMl = amountMl)
        _lastLogged.value = baby.byId(id)
    }

    fun logNappy(kind: BabyEventKind) = viewModelScope.launch {
        val id = baby.logEvent(kind)
        _lastLogged.value = baby.byId(id)
    }

    fun logSolids() = viewModelScope.launch {
        val id = baby.logEvent(BabyEventKind.SOLIDS)
        _lastLogged.value = baby.byId(id)
    }

    // ------------------------------------------------------------------ edits

    fun setAmount(event: BabyEventEntity, amountMl: Int?) = viewModelScope.launch {
        baby.setAmount(event, amountMl)
    }

    fun setNote(event: BabyEventEntity, note: String?) = viewModelScope.launch {
        baby.setNote(event, note)
    }

    /** Nudge a start time, for when logging happened a few minutes late. */
    fun shiftStart(event: BabyEventEntity, minutes: Long) = viewModelScope.launch {
        baby.adjustStart(event, event.startAt.plusSeconds(minutes * 60))
    }

    fun delete(event: BabyEventEntity) = viewModelScope.launch {
        baby.delete(event)
        if (_lastLogged.value?.id == event.id) _lastLogged.value = null
    }

    /** Removes the thing just logged — the mis-tap at 3am. */
    fun undoLast() = viewModelScope.launch {
        val last = _lastLogged.value ?: return@launch
        baby.deleteById(last.id)
        _lastLogged.value = null
    }

    fun clearUndo() {
        _lastLogged.value = null
    }

    /** Backdated logging: "she fell asleep at 7" after the fact. */
    fun logCompleted(
        kind: BabyEventKind,
        startAt: Instant,
        endAt: Instant,
        amountMl: Int? = null
    ) = viewModelScope.launch {
        val id = baby.logSession(kind, startAt, endAt, amountMl)
        _lastLogged.value = baby.byId(id)
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[APPLICATION_KEY] as TaskApp
                return BabyViewModel(app.container.babyRepository) as T
            }
        }
    }
}
