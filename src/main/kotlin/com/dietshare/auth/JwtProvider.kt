package com.dietshare.auth

import com.dietshare.common.AppProperties
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.Date

@Component
class JwtProvider(props: AppProperties, private val clock: Clock) {
    private val key = Keys.hmacShaKeyFor(props.jwt.secret.toByteArray())
    private val ttl = Duration.ofDays(props.jwt.ttlDays)

    fun issue(userId: Long): String {
        val now = clock.instant()
        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key)
            .compact()
    }

    fun parseUserId(token: String): Long? =
        try {
            Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token)
                .payload.subject
                .toLongOrNull()
        } catch (e: JwtException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}
