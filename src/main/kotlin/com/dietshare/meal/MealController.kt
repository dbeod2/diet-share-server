package com.dietshare.meal

import com.dietshare.auth.CurrentUser
import com.dietshare.quota.QuotaService
import com.dietshare.quota.QuotaStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class CreateMealRequest(
    @field:NotBlank @field:Size(max = 64)
    @field:Pattern(regexp = "^[A-Za-z0-9-]+$")
    val requestId: String,
    @field:NotBlank @field:Size(max = 200)
    val uploadKey: String,
    val mealType: MealType? = null,
    val takenAt: Instant? = null,
    @field:Size(max = 20)
    val roomIds: List<Long> = emptyList(),
)

data class UpdateMealRequest(
    val mealType: MealType? = null,
    @field:Size(max = 20)
    val roomIds: List<Long>? = null,
)

data class MealResponse(
    val id: Long,
    val mealType: MealType,
    val takenAt: Instant,
    val status: MealStatus,
    val imageUrl: String?,
    val thumbUrl: String?,
    /** Only the owner ever receives this. Rooms see the grade only. */
    val analysis: AnalysisResponse?,
    val roomIds: List<Long>,
    val reactionCount: Int,
)

data class DayResponse(val date: LocalDate, val meals: List<MealResponse>, val quota: QuotaStatus)

data class CalendarDayResponse(val date: LocalDate, val count: Int, val coverUrl: String?)

data class CalendarResponse(val month: String, val days: List<CalendarDayResponse>)

@RestController
class MealController(private val mealService: MealService, private val quotaService: QuotaService) {
    /** Step 1: get a URL to PUT the resized JPEG to. */
    @PostMapping("/uploads")
    fun createUpload(@CurrentUser userId: Long) = mealService.createUploadUrl(userId)

    /** Step 2: register the uploaded photo. Analysis runs in the background. */
    @PostMapping("/meals")
    fun create(@CurrentUser userId: Long, @Valid @RequestBody req: CreateMealRequest) = mealService.create(userId, req)

    @GetMapping("/meals/calendar")
    fun calendar(@CurrentUser userId: Long, @RequestParam month: YearMonth) = mealService.calendar(userId, month)

    @GetMapping("/meals")
    fun day(
        @CurrentUser userId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ) = mealService.day(userId, date)

    @GetMapping("/meals/{mealId}")
    fun detail(@CurrentUser userId: Long, @PathVariable mealId: Long) = mealService.detail(userId, mealId)

    @PatchMapping("/meals/{mealId}")
    fun update(
        @CurrentUser userId: Long,
        @PathVariable mealId: Long,
        @Valid @RequestBody req: UpdateMealRequest,
    ) = mealService.update(userId, mealId, req)

    @PostMapping("/meals/{mealId}/retry")
    fun retry(@CurrentUser userId: Long, @PathVariable mealId: Long) = mealService.retry(userId, mealId)

    @DeleteMapping("/meals/{mealId}")
    fun delete(@CurrentUser userId: Long, @PathVariable mealId: Long): ResponseEntity<Unit> {
        mealService.delete(userId, mealId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/quota")
    fun quota(@CurrentUser userId: Long) = quotaService.status(userId)
}
