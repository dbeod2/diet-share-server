package com.dietshare.quota

import com.dietshare.common.ApiException
import com.dietshare.common.AppProperties
import com.dietshare.common.Kst
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class QuotaStatus(val limit: Int, val used: Int, val remaining: Int)

/**
 * Daily analysis quota per user, counted in Redis.
 * A slot is reserved before a job is queued and given back when the analysis
 * fails, is not food, is rejected or is a duplicate photo (same KST day only).
 */
@Service
class QuotaService(
    private val redis: StringRedisTemplate,
    private val props: AppProperties,
    private val clock: Clock,
) {
    // Check-and-increment in one step so concurrent uploads cannot go over the limit.
    private val reserveScript = DefaultRedisScript(
        """
        local used = tonumber(redis.call('GET', KEYS[1]) or '0')
        if used >= tonumber(ARGV[1]) then return 0 end
        used = redis.call('INCR', KEYS[1])
        if used == 1 then redis.call('EXPIREAT', KEYS[1], ARGV[2]) end
        return 1
        """.trimIndent(),
        java.lang.Long::class.java,
    )

    private val refundScript = DefaultRedisScript(
        """
        local used = tonumber(redis.call('GET', KEYS[1]) or '0')
        if used > 0 then redis.call('DECR', KEYS[1]) end
        return 1
        """.trimIndent(),
        java.lang.Long::class.java,
    )

    fun today(): LocalDate = Kst.today(clock)

    /** Returns true when a slot was reserved for [date] (always today in practice). */
    fun tryReserve(userId: Long, date: LocalDate = today()): Boolean {
        val expireAt = Kst.startOf(date.plusDays(1)).epochSecond + 3600
        val result = redis.execute(
            reserveScript,
            listOf(key(userId, date)),
            props.quota.dailyLimit.toString(),
            expireAt.toString(),
        )
        return result?.toLong() == 1L
    }

    /** Gives a slot back. Ignored once the day has passed: yesterday's count no longer matters. */
    fun refund(userId: Long, date: LocalDate?) {
        if (date == null || date != today()) return
        redis.execute(refundScript, listOf(key(userId, date)))
    }

    fun status(userId: Long): QuotaStatus {
        val used = redis.opsForValue().get(key(userId, today()))?.toIntOrNull() ?: 0
        val limit = props.quota.dailyLimit
        return QuotaStatus(limit, used, maxOf(0, limit - used))
    }

    private fun key(userId: Long, date: LocalDate) = "quota:$userId:${date.format(DateTimeFormatter.BASIC_ISO_DATE)}"
}

/** Fixed-window limiter for invite-code guessing. */
@Service
class JoinRateLimiter(private val redis: StringRedisTemplate, private val props: AppProperties) {
    fun check(userId: Long) {
        val key = "join:$userId"
        val count: Long = redis.opsForValue().increment(key) ?: 1L
        if (count == 1L) redis.expire(key, Duration.ofSeconds(60))
        if (count > props.room.joinAttemptsPerMinute) {
            throw ApiException.tooMany("TOO_MANY_ATTEMPTS", "잠시 후 다시 시도해 주세요")
        }
    }
}
