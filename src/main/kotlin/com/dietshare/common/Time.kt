package com.dietshare.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** All day boundaries (quota reset, calendar days) follow Korea time. */
object Kst {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun today(clock: Clock): LocalDate = LocalDate.now(clock.withZone(ZONE))

    fun startOf(date: LocalDate): Instant = date.atStartOfDay(ZONE).toInstant()
}

@Configuration
class TimeConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun Instant.toOdt(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.instantOrNull(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

fun ResultSet.localDateOrNull(column: String): LocalDate? = getObject(column, LocalDate::class.java)
