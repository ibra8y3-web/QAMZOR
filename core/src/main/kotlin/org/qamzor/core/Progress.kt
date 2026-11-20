package org.qamzor.core

/**
 * Optional progress / cancellation hook for long-running engine operations.
 *
 * It is attached to the *calling thread* with [ArchiveEngine.withProgress], so no existing
 * engine signature changes and callers that do not use it behave exactly as before.
 */
interface ProgressListener {
    /** A new phase starts; [totalBytes] is the amount of payload that phase will process (0 when unknown). */
    fun onPhase(phase: String, totalBytes: Long) {}

    /** [deltaBytes] more payload bytes were processed in the current phase. */
    fun onBytes(deltaBytes: Long) {}

    /** Polled between chunks; return true to stop the operation as soon as possible. */
    fun isCancelled(): Boolean = false
}

/** Thrown when a [ProgressListener] reports cancellation. Partial output is removed by the callers. */
class OperationCancelledException(message: String = "تم إلغاء العملية") : RuntimeException(message)
