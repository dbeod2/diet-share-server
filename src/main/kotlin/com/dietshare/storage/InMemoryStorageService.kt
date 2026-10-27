package com.dietshare.storage

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** Used when no storage endpoint is configured: tests and quick local runs. Data is lost on restart. */
class InMemoryStorageService(private val clock: Clock) : StorageService {
    val objects = ConcurrentHashMap<String, ByteArray>()

    override fun presignUpload(key: String, contentType: String) =
        PresignedUpload(key, "memory://upload/$key", clock.instant().plusSeconds(300))

    override fun presignView(key: String) = "memory://view/$key"

    override fun exists(key: String) = objects.containsKey(key)

    override fun read(key: String): ByteArray = objects[key] ?: throw NoSuchElementException(key)

    override fun write(key: String, bytes: ByteArray, contentType: String) {
        objects[key] = bytes
    }

    override fun copy(fromKey: String, toKey: String) {
        objects[toKey] = read(fromKey)
    }

    override fun delete(keys: Collection<String>) {
        keys.forEach { objects.remove(it) }
    }
}
