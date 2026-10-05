package dev.pam.webrtc

import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.ArrayDeque

internal fun Map<String, WireValue>.text(key: String): String =
    (get(key) as? WireValue.Text)?.value ?: throw IllegalArgumentException("Missing $key")

internal fun Map<String, WireValue>.text(key: String, fallback: String): String =
    (get(key) as? WireValue.Text)?.value ?: fallback

internal fun Map<String, WireValue>.integer(key: String, fallback: Long): Long =
    (get(key) as? WireValue.Integer)?.value ?: fallback

internal fun Map<String, WireValue>.flag(key: String, fallback: Boolean = false): Boolean =
    (get(key) as? WireValue.Flag)?.value ?: fallback

internal fun ModuleCompletion.success(values: Map<String, WireValue> = emptyMap()) =
    complete(ModuleResultStatus.SUCCESS, WireMap.encode(values))

internal fun ModuleCompletion.failure(message: String) =
    complete(ModuleResultStatus.FAILURE, message.toByteArray())

/**
 * Push-style event channel: PHP keeps exactly one pending `next` completion and
 * the platform resolves it as soon as an event exists. Events produced while no
 * read is pending are buffered (bounded, oldest dropped first).
 */
internal class EventChannel(private val capacity: Int = 256) {
    private val queue = ArrayDeque<Map<String, WireValue>>()
    private var waiter: ModuleCompletion? = null
    private var closed = false

    fun next(completion: ModuleCompletion) {
        var event: Map<String, WireValue>? = null
        var replaced: ModuleCompletion? = null
        val wasClosed: Boolean
        synchronized(this) {
            wasClosed = closed
            if (!closed) {
                if (queue.isNotEmpty()) {
                    event = queue.removeFirst()
                } else {
                    replaced = waiter
                    waiter = completion
                }
            }
        }
        replaced?.failure("Event read replaced")
        when {
            wasClosed -> completion.failure("Event channel closed")
            event != null -> completion.success(event!!)
        }
    }

    fun offer(event: Map<String, WireValue>) {
        val receiver = synchronized(this) {
            if (closed) return
            val pending = waiter
            if (pending == null) {
                if (queue.size >= capacity) queue.removeFirst()
                queue.addLast(event)
            } else {
                waiter = null
            }
            pending
        }
        receiver?.success(event)
    }

    fun close() {
        val pending = synchronized(this) {
            if (closed) return
            closed = true
            queue.clear()
            waiter.also { waiter = null }
        }
        pending?.failure("Event channel closed")
    }

    @Synchronized
    fun pendingCount(): Int = queue.size
}
