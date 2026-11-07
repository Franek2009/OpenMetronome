package org.openmetronome.data

/** RETURNED means the checkpoint function returned; recovery may still be pending. */
enum class HistoryObservationStage { CREATED, CHECKPOINT_REQUESTED, CHECKPOINT_RETURNED, CHECKPOINT_FAILED }

data class HistoryObservation(
    val engineSessionId: Long, val historyId: String, val stage: HistoryObservationStage,
    val finished: Boolean, val interrupted: Boolean,
)
