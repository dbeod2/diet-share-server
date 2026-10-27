package com.dietshare.storage

import java.time.Instant

data class PresignedUpload(val key: String, val url: String, val expiresAt: Instant)

/**
 * Object storage (Cloudflare R2 in production, MinIO locally).
 *
 * Key layout:
 *  - temp/{userId}/{uuid}.jpg   uploaded by the app, removed by a 1-day lifecycle rule
 *  - photos/{userId}/{mealId}.jpg
 *  - thumbs/{userId}/{mealId}.jpg
 */
interface StorageService {
    fun presignUpload(key: String, contentType: String): PresignedUpload
    fun presignView(key: String): String
    fun exists(key: String): Boolean
    fun read(key: String): ByteArray
    fun write(key: String, bytes: ByteArray, contentType: String)
    fun copy(fromKey: String, toKey: String)
    fun delete(keys: Collection<String>)
}

object StorageKeys {
    fun tempPrefix(userId: Long) = "temp/$userId/"
    fun photo(userId: Long, mealId: Long) = "photos/$userId/$mealId.jpg"
    fun thumb(userId: Long, mealId: Long) = "thumbs/$userId/$mealId.jpg"
}
