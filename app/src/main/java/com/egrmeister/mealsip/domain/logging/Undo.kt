package com.egrmeister.mealsip.domain.logging

import com.egrmeister.mealsip.domain.model.WaterEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** A reversible action offered through an Undo snackbar. */
sealed interface UndoAction {
    val token: Long
    val message: String

    /** An entry was recorded; undo deletes it. */
    data class Added(override val token: Long, override val message: String, val entryId: Long) : UndoAction

    /** An entry was deleted; undo restores the exact record. */
    data class Deleted(override val token: Long, override val message: String, val entry: WaterEntry) : UndoAction
}

/**
 * Holds the latest undoable action. [consume] hands an action out at most once, so a repeated
 * Undo tap can never restore or delete twice.
 */
class UndoCenter {
    private val tokens = AtomicLong(0)
    private val _pending = MutableStateFlow<UndoAction?>(null)
    val pending: StateFlow<UndoAction?> = _pending.asStateFlow()

    fun nextToken(): Long = tokens.incrementAndGet()

    fun offer(action: UndoAction) {
        _pending.value = action
    }

    @Synchronized
    fun consume(token: Long): UndoAction? {
        val current = _pending.value ?: return null
        if (current.token != token) return null
        _pending.value = null
        return current
    }

    @Synchronized
    fun dismiss(token: Long) {
        if (_pending.value?.token == token) _pending.value = null
    }

    fun clear() {
        _pending.value = null
    }
}

/** Executes an undo action exactly once. */
class UndoExecutor(private val log: WaterLogService) {
    suspend fun perform(action: UndoAction): Boolean = when (action) {
        is UndoAction.Added -> log.delete(action.entryId) != null
        is UndoAction.Deleted -> log.restore(action.entry)
    }
}
