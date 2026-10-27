package com.dietshare.meal

import com.dietshare.analysis.AnalysisRepository
import com.dietshare.analysis.JobQueue
import com.dietshare.analysis.MealAnalysis
import com.dietshare.common.ApiException
import com.dietshare.common.Kst
import com.dietshare.quota.QuotaService
import com.dietshare.room.RoomRepository
import com.dietshare.room.ShareRepository
import com.dietshare.storage.PresignedUpload
import com.dietshare.storage.StorageKeys
import com.dietshare.storage.StorageService
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Service
class MealService(
    private val meals: MealRepository,
    private val shares: ShareRepository,
    private val rooms: RoomRepository,
    private val analyses: AnalysisRepository,
    private val queue: JobQueue,
    private val quota: QuotaService,
    private val storage: StorageService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun createUploadUrl(userId: Long): PresignedUpload =
        storage.presignUpload(StorageKeys.tempPrefix(userId) + UUID.randomUUID() + ".jpg", "image/jpeg")

    /**
     * Registers an uploaded photo as a meal.
     * Idempotent per (user, requestId): a network retry returns the first result instead of a second meal.
     */
    fun create(userId: Long, req: CreateMealRequest): MealResponse {
        meals.findByRequestId(userId, req.requestId)?.let { return detail(userId, it.id) }

        if (!req.uploadKey.startsWith(StorageKeys.tempPrefix(userId))) {
            throw ApiException.badRequest("INVALID_UPLOAD_KEY", "업로드 정보가 올바르지 않아요")
        }
        if (!storage.exists(req.uploadKey)) {
            throw ApiException.badRequest("UPLOAD_NOT_FOUND", "사진 업로드가 끝나지 않았어요")
        }
        val roomIds = req.roomIds.distinct()
        requireMemberOfAll(userId, roomIds)

        val takenAt = req.takenAt ?: clock.instant()
        val mealType = req.mealType ?: MealTypes.suggest(takenAt)
        val today = quota.today()
        val reserved = quota.tryReserve(userId, today)

        val mealId = try {
            tx.execute {
                val id = meals.insert(
                    userId = userId,
                    requestId = req.requestId,
                    mealType = mealType,
                    takenAt = takenAt,
                    status = if (reserved) MealStatus.PENDING else MealStatus.LIMITED,
                    tempKey = req.uploadKey,
                    quotaDate = if (reserved) today else null,
                )
                shares.insert(id, roomIds, visibleNow = false) // visible only after moderation
                if (reserved) queue.enqueue(id)
                id
            }!!
        } catch (e: DuplicateKeyException) {
            // Two identical requests raced; the other one won.
            if (reserved) quota.refund(userId, today)
            val existing = meals.findByRequestId(userId, req.requestId) ?: throw e
            return detail(userId, existing.id)
        } catch (e: Exception) {
            if (reserved) quota.refund(userId, today)
            throw e
        }

        if (!reserved) keepPrivately(userId, mealId, req.uploadKey)
        return detail(userId, mealId)
    }

    /** Over the daily limit: the photo is kept for the user only, without analysis. */
    private fun keepPrivately(userId: Long, mealId: Long, tempKey: String) {
        val photo = StorageKeys.photo(userId, mealId)
        try {
            storage.copy(tempKey, photo)
            storage.delete(listOf(tempKey))
            meals.moveToPrivateImage(mealId, photo)
        } catch (e: Exception) {
            log.warn("Could not move limited meal {} out of temp storage", mealId, e)
        }
    }

    /** "다시 시도" (FAILED) or "내일 분석하기" (LIMITED). Uses today's quota. */
    fun retry(userId: Long, mealId: Long): MealResponse {
        val meal = owned(userId, mealId)
        if (meal.status != MealStatus.FAILED && meal.status != MealStatus.LIMITED) {
            throw ApiException.conflict("NOT_RETRYABLE", "다시 분석할 수 없는 기록이에요")
        }
        val today = quota.today()
        if (!quota.tryReserve(userId, today)) {
            throw ApiException.tooMany("QUOTA_EXCEEDED", "오늘 분석 횟수를 다 썼어요")
        }
        val queued = tx.execute {
            val ok = meals.requeue(mealId, today)
            if (ok) queue.enqueue(mealId)
            ok
        } ?: false
        if (!queued) {
            quota.refund(userId, today)
            throw ApiException.conflict("NOT_RETRYABLE", "다시 분석할 수 없는 기록이에요")
        }
        return detail(userId, mealId)
    }

    fun detail(userId: Long, mealId: Long): MealResponse {
        val meal = owned(userId, mealId)
        return toResponses(listOf(meal)).first()
    }

    fun day(userId: Long, date: LocalDate): DayResponse {
        val list = meals.findInRange(userId, Kst.startOf(date), Kst.startOf(date.plusDays(1)))
        return DayResponse(date, toResponses(list), quota.status(userId))
    }

    fun calendar(userId: Long, month: YearMonth): CalendarResponse {
        val from = Kst.startOf(month.atDay(1))
        val to = Kst.startOf(month.plusMonths(1).atDay(1))
        val days = meals.calendar(userId, from, to).map {
            CalendarDayResponse(it.date, it.count, it.coverKey?.let(storage::presignView))
        }
        return CalendarResponse(month.toString(), days)
    }

    fun update(userId: Long, mealId: Long, req: UpdateMealRequest): MealResponse {
        val meal = owned(userId, mealId)
        req.mealType?.let { meals.updateMealType(mealId, it) }
        req.roomIds?.let { wanted ->
            val target = wanted.distinct()
            requireMemberOfAll(userId, target)
            val current = shares.roomIdsOf(mealId).toSet()
            tx.executeWithoutResult {
                shares.delete(mealId, current - target.toSet()) // reactions cascade
                shares.insert(mealId, target.filterNot { it in current }, visibleNow = meal.status == MealStatus.DONE)
            }
        }
        return detail(userId, mealId)
    }

    fun delete(userId: Long, mealId: Long) {
        val meal = owned(userId, mealId)
        meals.delete(mealId) // shares, reactions, analysis and jobs cascade
        storage.delete(listOfNotNull(meal.tempKey, meal.imageKey, meal.thumbKey))
    }

    fun deleteAllForUser(userId: Long) {
        storage.delete(meals.storageKeysOfUser(userId))
    }

    private fun owned(userId: Long, mealId: Long): Meal {
        val meal = meals.findById(mealId) ?: throw ApiException.notFound("MEAL_NOT_FOUND", "기록을 찾을 수 없어요")
        if (meal.userId != userId) throw ApiException.notFound("MEAL_NOT_FOUND", "기록을 찾을 수 없어요")
        return meal
    }

    private fun requireMemberOfAll(userId: Long, roomIds: List<Long>) {
        if (roomIds.isEmpty()) return
        val joined = rooms.memberRoomIds(userId, roomIds)
        if (joined.size != roomIds.size) throw ApiException.forbidden("NOT_ROOM_MEMBER", "참여하지 않은 방이 있어요")
    }

    private fun toResponses(list: List<Meal>): List<MealResponse> {
        val ids = list.map { it.id }
        val analysisById = analyses.findAll(ids)
        val roomsById = shares.roomIdsOf(ids)
        val reactions = shares.reactionTotals(ids)
        return list.map { meal ->
            MealResponse(
                id = meal.id,
                mealType = meal.mealType,
                takenAt = meal.takenAt,
                status = meal.status,
                imageUrl = (meal.imageKey ?: meal.tempKey)?.let(storage::presignView),
                thumbUrl = (meal.thumbKey ?: meal.imageKey ?: meal.tempKey)?.let(storage::presignView),
                analysis = analysisById[meal.id]?.let { AnalysisResponse.from(it) },
                roomIds = roomsById[meal.id].orEmpty(),
                reactionCount = reactions[meal.id] ?: 0,
            )
        }
    }
}

object MealTypes {
    /** Default meal type from the KST hour the photo was taken. */
    fun suggest(takenAt: Instant): MealType =
        when (takenAt.atZone(Kst.ZONE).hour) {
            in 5..9 -> MealType.BREAKFAST
            in 10..14 -> MealType.LUNCH
            in 17..20 -> MealType.DINNER
            else -> MealType.SNACK
        }
}

data class AnalysisResponse(
    val foods: List<String>,
    val grade: com.dietshare.analysis.Grade,
    val nutrients: Map<com.dietshare.analysis.Nutrient, com.dietshare.analysis.Level>,
    val feedback: String?,
) {
    companion object {
        fun from(a: MealAnalysis) = AnalysisResponse(a.foods, a.grade, a.nutrients, a.feedback)
    }
}
