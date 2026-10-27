package com.dietshare.common

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app")
data class AppProperties(
    val jwt: Jwt = Jwt(),
    val auth: Auth = Auth(),
    val quota: Quota = Quota(),
    val worker: Worker = Worker(),
    val storage: Storage = Storage(),
    val ai: Ai = Ai(),
    val room: Room = Room(),
) {
    data class Jwt(
        val secret: String = "local-dev-secret-change-me-0123456789",
        val ttlDays: Long = 30,
    )

    data class Auth(
        val devLoginEnabled: Boolean = false,
    )

    data class Quota(
        val dailyLimit: Int = 10,
    )

    data class Worker(
        val enabled: Boolean = true,
        val pollDelayMs: Long = 2000,
        val batchSize: Int = 5,
        val maxAttempts: Int = 3,
        val stuckAfterMinutes: Int = 5,
        val keepDoneDays: Int = 7,
    )

    data class Storage(
        val endpoint: String = "",
        val region: String = "auto",
        val bucket: String = "diet-share",
        val accessKey: String = "",
        val secretKey: String = "",
        val uploadUrlMinutes: Long = 5,
        val viewUrlMinutes: Long = 60,
    )

    data class Ai(
        val provider: String = "stub",
        val apiKey: String = "",
        val model: String = "",
    )

    data class Room(
        val maxMembers: Int = 5,
        val joinAttemptsPerMinute: Int = 5,
    )
}
