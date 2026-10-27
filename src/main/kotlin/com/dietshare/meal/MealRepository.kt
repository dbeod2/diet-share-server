package com.dietshare.meal

import com.dietshare.common.instant
import com.dietshare.common.localDateOrNull
import com.dietshare.common.toOdt
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }

enum class MealStatus { PENDING, ANALYZING, DONE, FAILED, NOT_FOOD, LIMITED, REJECTED }

data class Meal(
    val id: Long,
    val userId: Long,
    val requestId: String,
    val mealType: MealType,
    val takenAt: Instant,
    val status: MealStatus,
    val tempKey: String?,
    val imageKey: String?,
    val thumbKey: String?,
    val imageHash: String?,
    val quotaDate: LocalDate?,
)

data class CalendarDay(val date: LocalDate, val count: Int, val coverKey: String?)

@Repository
class MealRepository(private val jdbc: JdbcClient) {
    private val mealMapper = RowMapper { rs, _ ->
        Meal(
            id = rs.getLong("id"),
            userId = rs.getLong("user_id"),
            requestId = rs.getString("request_id"),
            mealType = MealType.valueOf(rs.getString("meal_type")),
            takenAt = rs.instant("taken_at"),
            status = MealStatus.valueOf(rs.getString("status")),
            tempKey = rs.getString("temp_key"),
            imageKey = rs.getString("image_key"),
            thumbKey = rs.getString("thumb_key"),
            imageHash = rs.getString("image_hash"),
            quotaDate = rs.localDateOrNull("quota_date"),
        )
    }

    fun findById(id: Long): Meal? =
        jdbc.sql("SELECT * FROM meals WHERE id = :id").param("id", id)
            .query(mealMapper).optional().orElse(null)

    fun findByRequestId(userId: Long, requestId: String): Meal? =
        jdbc.sql("SELECT * FROM meals WHERE user_id = :userId AND request_id = :requestId")
            .param("userId", userId)
            .param("requestId", requestId)
            .query(mealMapper).optional().orElse(null)

    fun insert(
        userId: Long,
        requestId: String,
        mealType: MealType,
        takenAt: Instant,
        status: MealStatus,
        tempKey: String,
        quotaDate: LocalDate?,
    ): Long =
        jdbc.sql(
            """
            INSERT INTO meals (user_id, request_id, meal_type, taken_at, status, temp_key, quota_date)
            VALUES (:userId, :requestId, :mealType, :takenAt, :status, :tempKey, :quotaDate)
            RETURNING id
            """.trimIndent()
        )
            .param("userId", userId)
            .param("requestId", requestId)
            .param("mealType", mealType.name)
            .param("takenAt", takenAt.toOdt())
            .param("status", status.name)
            .param("tempKey", tempKey)
            .param("quotaDate", quotaDate)
            .query(java.lang.Long::class.java).single().toLong()

    /** PENDING -> ANALYZING. Returns false if the meal was deleted or already handled. */
    fun markAnalyzing(id: Long): Boolean =
        jdbc.sql("UPDATE meals SET status = 'ANALYZING', updated_at = now() WHERE id = :id AND status IN ('PENDING', 'ANALYZING')")
            .param("id", id).update() == 1

    /** ANALYZING -> DONE / NOT_FOOD with the stored photo keys. */
    fun completeWithImage(id: Long, status: MealStatus, imageKey: String, thumbKey: String, hash: String): Boolean =
        jdbc.sql(
            """
            UPDATE meals
            SET status = :status, image_key = :imageKey, thumb_key = :thumbKey, image_hash = :hash,
                temp_key = NULL, updated_at = now()
            WHERE id = :id AND status = 'ANALYZING'
            """.trimIndent()
        )
            .param("id", id)
            .param("status", status.name)
            .param("imageKey", imageKey)
            .param("thumbKey", thumbKey)
            .param("hash", hash)
            .update() == 1

    /** Inappropriate photo: keep only a tombstone row without any image so the app can tell the user. */
    fun reject(id: Long): Boolean =
        jdbc.sql(
            """
            UPDATE meals
            SET status = 'REJECTED', temp_key = NULL, image_key = NULL, thumb_key = NULL, updated_at = now()
            WHERE id = :id AND status = 'ANALYZING'
            """.trimIndent()
        ).param("id", id).update() == 1

    fun markFailed(id: Long, imageKey: String?): Boolean =
        jdbc.sql(
            """
            UPDATE meals
            SET status = 'FAILED',
                image_key = COALESCE(CAST(:imageKey AS varchar), image_key),
                temp_key = CASE WHEN CAST(:imageKey AS varchar) IS NULL THEN temp_key ELSE NULL END,
                updated_at = now()
            WHERE id = :id AND status = 'ANALYZING'
            """.trimIndent()
        )
            .param("id", id)
            .param("imageKey", imageKey)
            .update() == 1

    /** LIMITED meals keep their photo privately right away (the temp upload expires in a day). */
    fun moveToPrivateImage(id: Long, imageKey: String) {
        jdbc.sql("UPDATE meals SET image_key = :imageKey, temp_key = NULL, updated_at = now() WHERE id = :id")
            .param("id", id)
            .param("imageKey", imageKey)
            .update()
    }

    /** FAILED / LIMITED -> PENDING for a manual retry. */
    fun requeue(id: Long, quotaDate: LocalDate): Boolean =
        jdbc.sql(
            """
            UPDATE meals SET status = 'PENDING', quota_date = :quotaDate, updated_at = now()
            WHERE id = :id AND status IN ('FAILED', 'LIMITED')
            """.trimIndent()
        )
            .param("id", id)
            .param("quotaDate", quotaDate)
            .update() == 1

    fun resetToPending(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        jdbc.sql("UPDATE meals SET status = 'PENDING', updated_at = now() WHERE id IN (:ids) AND status = 'ANALYZING'")
            .param("ids", ids)
            .update()
    }

    fun updateMealType(id: Long, mealType: MealType) {
        jdbc.sql("UPDATE meals SET meal_type = :mealType, updated_at = now() WHERE id = :id")
            .param("id", id)
            .param("mealType", mealType.name)
            .update()
    }

    fun delete(id: Long) {
        jdbc.sql("DELETE FROM meals WHERE id = :id").param("id", id).update()
    }

    fun findDoneByHash(userId: Long, hash: String, excludeId: Long): Meal? =
        jdbc.sql(
            """
            SELECT * FROM meals
            WHERE user_id = :userId AND image_hash = :hash AND status = 'DONE' AND id <> :excludeId
            ORDER BY id LIMIT 1
            """.trimIndent()
        )
            .param("userId", userId)
            .param("hash", hash)
            .param("excludeId", excludeId)
            .query(mealMapper).optional().orElse(null)

    fun findInRange(userId: Long, from: Instant, to: Instant): List<Meal> =
        jdbc.sql(
            """
            SELECT * FROM meals
            WHERE user_id = :userId AND taken_at >= :from AND taken_at < :to AND status <> 'REJECTED'
            ORDER BY taken_at
            """.trimIndent()
        )
            .param("userId", userId)
            .param("from", from.toOdt())
            .param("to", to.toOdt())
            .query(mealMapper).list()

    fun calendar(userId: Long, from: Instant, to: Instant): List<CalendarDay> =
        jdbc.sql(
            """
            SELECT d,
                   count(*) AS cnt,
                   (array_agg(cover ORDER BY taken_at) FILTER (WHERE cover IS NOT NULL))[1] AS cover_key
            FROM (
                SELECT (taken_at AT TIME ZONE 'Asia/Seoul')::date AS d,
                       COALESCE(thumb_key, image_key) AS cover,
                       taken_at
                FROM meals
                WHERE user_id = :userId AND taken_at >= :from AND taken_at < :to AND status <> 'REJECTED'
            ) t
            GROUP BY d
            ORDER BY d
            """.trimIndent()
        )
            .param("userId", userId)
            .param("from", from.toOdt())
            .param("to", to.toOdt())
            .query(RowMapper { rs, _ ->
                CalendarDay(rs.getObject("d", LocalDate::class.java), rs.getInt("cnt"), rs.getString("cover_key"))
            }).list()

    /** Every object key a user owns, for account deletion. */
    fun storageKeysOfUser(userId: Long): List<String> =
        jdbc.sql(
            """
            SELECT k FROM (
                SELECT temp_key AS k FROM meals WHERE user_id = :userId
                UNION ALL SELECT image_key FROM meals WHERE user_id = :userId
                UNION ALL SELECT thumb_key FROM meals WHERE user_id = :userId
            ) keys WHERE k IS NOT NULL
            """.trimIndent()
        )
            .param("userId", userId)
            .query(String::class.java).list()
}
