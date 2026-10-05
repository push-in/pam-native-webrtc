import Foundation
import PamNative

extension Dictionary where Key == String, Value == WireValue {
    func text(_ key: String) throws -> String {
        guard case let .text(value)? = self[key] else { throw RtcError("Missing \(key)") }
        return value
    }

    func text(_ key: String, _ fallback: String) -> String {
        if case let .text(value)? = self[key] { return value }
        return fallback
    }

    func integer(_ key: String, _ fallback: Int64) -> Int64 {
        if case let .integer(value)? = self[key] { return value }
        return fallback
    }

    func flag(_ key: String, _ fallback: Bool = false) -> Bool {
        if case let .flag(value)? = self[key] { return value }
        return fallback
    }
}

struct RtcError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

func succeed(_ completion: ModuleCompletion, _ values: [String: WireValue] = [:]) {
    completion(.success, (try? WireMap.encode(values)) ?? Data())
}

func fail(_ completion: ModuleCompletion, _ message: String) {
    completion(.failure, Data(message.utf8))
}

/// Push-style event channel: PHP keeps one pending `next` read that resolves
/// as soon as an event exists; events without a reader are buffered (bounded,
/// oldest dropped first). Mirrors the Android EventChannel.
final class EventChannel: @unchecked Sendable {
    private let capacity: Int
    private let lock = NSLock()
    private var queue: [[String: WireValue]] = []
    private var waiter: ModuleCompletion?
    private var closed = false

    init(capacity: Int = 256) {
        self.capacity = capacity
    }

    func next(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        if closed {
            lock.unlock()
            fail(completion, "Event channel closed")
            return
        }
        if !queue.isEmpty {
            let event = queue.removeFirst()
            lock.unlock()
            succeed(completion, event)
            return
        }
        let replaced = waiter
        waiter = completion
        lock.unlock()
        if let replaced { fail(replaced, "Event read replaced") }
    }

    func offer(_ event: [String: WireValue]) {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        let receiver = waiter
        if receiver == nil {
            if queue.count >= capacity { queue.removeFirst() }
            queue.append(event)
        } else {
            waiter = nil
        }
        lock.unlock()
        if let receiver { succeed(receiver, event) }
    }

    func close() {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        closed = true
        queue.removeAll()
        let pending = waiter
        waiter = nil
        lock.unlock()
        if let pending { fail(pending, "Event channel closed") }
    }

    var pendingCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return queue.count
    }
}
