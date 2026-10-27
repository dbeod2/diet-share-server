package com.dietshare.room

import com.dietshare.common.ApiException
import com.dietshare.meal.MealRepository
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

enum class ReactionType { LIKE, GREAT, CHEER, YUMMY }

enum class ReportReason { INAPPROPRIATE, SPAM, OTHER }

/** Reactions and reports on a meal shared in a room. */
@Service
class SocialService(
    private val jdbc: JdbcClient,
    private val roomService: RoomService,
    private val shares: ShareRepository,
    private val meals: MealRepository,
) {
    /** Pressing the same reaction twice is a no-op thanks to the primary key. */
    fun react(userId: Long, roomId: Long, mealId: Long, type: ReactionType) {
        requireVisibleShare(userId, roomId, mealId)
        jdbc.sql(
            """
            INSERT INTO reactions (meal_id, room_id, user_id, type) VALUES (:mealId, :roomId, :userId, :type)
            ON CONFLICT DO NOTHING
            """.trimIndent()
        )
            .param("mealId", mealId)
            .param("roomId", roomId)
            .param("userId", userId)
            .param("type", type.name)
            .update()
    }

    fun unreact(userId: Long, roomId: Long, mealId: Long, type: ReactionType) {
        roomService.requireMember(roomId, userId)
        jdbc.sql(
            "DELETE FROM reactions WHERE meal_id = :mealId AND room_id = :roomId AND user_id = :userId AND type = :type"
        )
            .param("mealId", mealId)
            .param("roomId", roomId)
            .param("userId", userId)
            .param("type", type.name)
            .update()
    }

    fun reactors(userId: Long, roomId: Long, mealId: Long): List<ReactorResponse> {
        requireVisibleShare(userId, roomId, mealId)
        return jdbc.sql(
            """
            SELECT r.user_id, u.nickname, r.type
            FROM reactions r JOIN users u ON u.id = r.user_id
            WHERE r.meal_id = :mealId AND r.room_id = :roomId
            ORDER BY r.created_at
            """.trimIndent()
        )
            .param("mealId", mealId)
            .param("roomId", roomId)
            .query(RowMapper { rs, _ ->
                ReactorResponse(rs.getLong("user_id"), rs.getString("nickname"), ReactionType.valueOf(rs.getString("type")))
            }).list()
    }

    /**
     * Hides the meal from the reporter at once; from everyone in the room once enough members report it.
     * Reports stay in the table with status OPEN for review.
     */
    fun report(userId: Long, roomId: Long, mealId: Long, reason: ReportReason) {
        requireVisibleShare(userId, roomId, mealId)
        val meal = meals.findById(mealId) ?: throw shareNotFound()
        if (meal.userId == userId) throw ApiException.badRequest("CANNOT_REPORT_OWN", "내 기록은 신고할 수 없어요")
        jdbc.sql(
            """
            INSERT INTO reports (meal_id, room_id, reporter_id, reason) VALUES (:mealId, :roomId, :userId, :reason)
            ON CONFLICT (meal_id, room_id, reporter_id) DO NOTHING
            """.trimIndent()
        )
            .param("mealId", mealId)
            .param("roomId", roomId)
            .param("userId", userId)
            .param("reason", reason.name)
            .update()
    }

    private fun requireVisibleShare(userId: Long, roomId: Long, mealId: Long) {
        roomService.requireMember(roomId, userId)
        if (!shares.isVisible(mealId, roomId)) throw shareNotFound()
    }

    private fun shareNotFound() = ApiException.notFound("SHARE_NOT_FOUND", "방에서 기록을 찾을 수 없어요")
}
