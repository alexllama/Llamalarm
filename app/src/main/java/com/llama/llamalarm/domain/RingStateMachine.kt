package com.llama.llamalarm.domain

/**
 * Pure state machine for a single fired alarm instance.
 *
 * Rules:
 *  - Initial (SCHEDULED) ring lasts 2 minutes. If unattended: silence, wait 5 minutes, ring once
 *    more (AUTO_RETRY). If the retry is unattended too, give up.
 *  - If the user snoozes during the initial ring, the 2min/5min retry path is cancelled.
 *  - A snoozed ring that is missed (unattended for 2 minutes) is re-snoozed for the same duration,
 *    up to 3 misses, then given up.
 *  - At most 5 user-triggered snoozes per fired instance.
 */
object RingStateMachine {
    const val AUTO_SILENCE_MS = 2 * 60_000L
    const val RETRY_DELAY_MS = 5 * 60_000L
    const val MAX_USER_SNOOZES = 5
    const val MAX_MISSES = 3

    fun startScheduled(alarmId: String, instanceTriggerAt: Long, now: Long): RingInstance =
        RingInstance(
            alarmId = alarmId,
            instanceTriggerAt = instanceTriggerAt,
            source = RingSource.SCHEDULED,
            phase = RingPhase.RINGING,
            nextActionAt = now + AUTO_SILENCE_MS,
            updatedAt = now,
        )

    fun canSnooze(instance: RingInstance): Boolean =
        instance.phase == RingPhase.RINGING && instance.userSnoozeCount < MAX_USER_SNOOZES

    /** Applies a user snooze. Returns null when snoozing is not allowed. */
    fun snooze(instance: RingInstance, now: Long, durationMs: Long): RingInstance? {
        if (!canSnooze(instance)) return null
        return instance.copy(
            source = RingSource.USER_SNOOZE,
            phase = RingPhase.WAITING_SNOOZE,
            userSnoozeCount = instance.userSnoozeCount + 1,
            lastSnoozeMs = durationMs,
            initialRetrySuppressed = true,
            nextActionAt = now + durationMs,
            updatedAt = now,
        )
    }

    /** The alarm rang again after a retry/snooze wait. Returns the ringing instance. */
    fun onRingStarted(instance: RingInstance, source: RingSource, now: Long): RingInstance =
        instance.copy(
            source = source,
            phase = RingPhase.RINGING,
            nextActionAt = now + AUTO_SILENCE_MS,
            updatedAt = now,
        )

    /**
     * The 2-minute ring window elapsed without interaction.
     * Returns the updated instance, or null if the instance is finished.
     */
    fun onRingTimeout(instance: RingInstance, now: Long): RingInstance? {
        if (instance.phase != RingPhase.RINGING) return instance
        return when (instance.source) {
            RingSource.SCHEDULED -> {
                if (instance.initialRetrySuppressed) {
                    null
                } else {
                    instance.copy(
                        source = RingSource.AUTO_RETRY,
                        phase = RingPhase.WAITING_RETRY,
                        nextActionAt = now + RETRY_DELAY_MS,
                        updatedAt = now,
                    )
                }
            }

            RingSource.AUTO_RETRY -> null

            RingSource.USER_SNOOZE, RingSource.MISSED_RESNOOZE -> {
                val misses = instance.missCount + 1
                val duration = instance.lastSnoozeMs
                if (misses >= MAX_MISSES || duration == null) {
                    null
                } else {
                    instance.copy(
                        source = RingSource.MISSED_RESNOOZE,
                        phase = RingPhase.WAITING_SNOOZE,
                        missCount = misses,
                        nextActionAt = now + duration,
                        updatedAt = now,
                    )
                }
            }
        }
    }

    /** Whether a waiting instance is due for its next ring (as opposed to giving up). */
    fun isWaiting(instance: RingInstance): Boolean =
        instance.phase == RingPhase.WAITING_RETRY || instance.phase == RingPhase.WAITING_SNOOZE
}
