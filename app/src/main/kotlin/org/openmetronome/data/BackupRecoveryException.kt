package org.openmetronome.data

/** A rollback failed. Keep recovery files and never claim that data is unchanged. */
internal class BackupRecoveryException(cause: Throwable) : Exception("Nie udało się cofnąć wszystkich zmian kopii zapasowej.", cause)
