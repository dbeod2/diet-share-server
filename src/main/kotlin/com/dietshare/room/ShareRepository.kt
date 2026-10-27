package com.dietshare.room

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** meal_shares: links one stored meal to the rooms it is shared in. */
@Repository
class ShareRepository(private val jdbc: JdbcClient) {
    fun insert(mealId: Long, roomIds: Collection<Long>, visibleNow: Boolean) {
        roomIds.forEach { roomId ->
            jdbc.sql(
                """
                INSERT INTO meal_shares (meal_id, room_id, visible_at)
                VALUES (:mealId, :roomId, CASE WHEN :visible THEN now() ELSE NULL END)
                ON CONFLICT (meal_id, room_id) DO NOTHING
                """.trimIndent()
            )
                .param("mealId", mealId)
                .param("roomId", roomId)
                .param("visible", visibleNow)
                .update()
        }
    }

    fun delete(mealId: Long, roomIds: Collection<Long>) {
        if (roomIds.isEmpty()) return
        jdbc.sql("DELETE FROM meal_shares WHERE meal_id = :mealId AND room_id IN (:ids)")
            .param("mealId", mealId)
            .param("ids", roomIds)
            .update()
    }

    fun deleteAllOf(mealId: Long) {
        jdbc.sql("DELETE FROM meal_shares WHERE meal_id = :mealId").param("mealId", mealId).update()
    }

    fun roomIdsOf(mealId: Long): List<Long> =
        jdbc.sql("SELECT room_id FROM meal_shares WHERE meal_id = :mealId ORDER BY room_id")
            .param("mealId", mealId)
            .query(java.lang.Long::class.java).list().map { it.toLong() }

    fun roomIdsOf(mealIds: Collection<Long>): Map<Long, List<Long>> {
        if (mealIds.isEmpty()) return emptyMap()
        return jdbc.sql("SELECT meal_id, room_id FROM meal_shares WHERE meal_id IN (:ids)")
            .param("ids", mealIds)
            .query(RowMapper { rs, _ -> rs.getLong("meal_id") to rs.getLong("room_id") })
            .list()
            .groupBy({ it.first }, { it.second })
    }

    /** Moderation passed: the meal appears in every room it was shared to. */
    fun makeVisible(mealId: Long) {
        jdbc.sql("UPDATE meal_shares SET visible_at = now() WHERE meal_id = :mealId AND visible_at IS NULL")
            .param("mealId", mealId).update()
    }

    fun isVisible(mealId: Long, roomId: Long): Boolean =
        jdbc.sql("SELECT count(*) FROM meal_shares WHERE meal_id = :mealId AND room_id = :roomId AND visible_at IS NOT NULL")
            .param("mealId", mealId)
            .param("roomId", roomId)
            .query(java.lang.Long::class.java).single().toLong() > 0

    /** When a user leaves a room, their meals disappear from it (reactions cascade). */
    fun deleteUserSharesInRoom(userId: Long, roomId: Long) {
        jdbc.sql(
            """
            DELETE FROM meal_shares s USING meals m
            WHERE s.meal_id = m.id AND m.user_id = :userId AND s.room_id = :roomId
            """.trimIndent()
        )
            .param("userId", userId)
            .param("roomId", roomId)
            .update()
    }

    fun deleteUserReactionsInRoom(userId: Long, roomId: Long) {
        jdbc.sql("DELETE FROM reactions WHERE user_id = :userId AND room_id = :roomId")
            .param("userId", userId)
            .param("roomId", roomId)
            .update()
    }

    /** Total reactions per meal across all rooms, for the owner's own views. */
    fun reactionTotals(mealIds: Collection<Long>): Map<Long, Int> {
        if (mealIds.isEmpty()) return emptyMap()
        return jdbc.sql("SELECT meal_id, count(*) AS cnt FROM reactions WHERE meal_id IN (:ids) GROUP BY meal_id")
            .param("ids", mealIds)
            .query(RowMapper { rs, _ -> rs.getLong("meal_id") to rs.getInt("cnt") })
            .list().toMap()
    }
}
