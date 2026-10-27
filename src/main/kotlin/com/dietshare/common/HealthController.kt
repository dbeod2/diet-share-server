package com.dietshare.common

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Lightweight endpoint the app calls on launch to wake the free Render instance. */
@RestController
class HealthController {
    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")
}
