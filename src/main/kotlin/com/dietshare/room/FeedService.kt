package com.dietshare.room

import com.dietshare.analysis.Grade
import com.dietshare.common.ApiException
import com.dietshare.common.instant
import com.dietshare.common.toOdt
import com.dietshare.meal.MealType
import com.dietshare.storage.StorageService
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Instant

private data class FeedRow(
    val mealId: Long,
    val visibleAt: Instant,
    val authorId: Long,
    val authorNickname: String,
    val mealType: MealType,
    val takenAt: Instant,
    val imageKey: String?,
    val thumbKey: String?,
    val grade: Grade?,
)

/**
 * Room feed, newest first, keyset-paginated on (visible_at, meal_id).
 * Hidden: meals the viewer reported, and meals reported by [HIDE_AFTER_REPORTS] or more members.
 * Rooms only see the grade; the AI feedback stays private to the author.
 */
@Service
class FeedService(
    private val jdbc: JdbcClient,
    private val roomService: RoomService,
    private val storage: StorageService,
) {
    private val rowMapper = RowMapper { rs, _ ->
        FeedRow(
            mealId = rs.getLong("meal_id"),
            visibleAt = rs.instant("visible_at"),
            authorId = rs.getLong("user_id"),
            authorNickname = rs.getString("nickname"),
            mealType = MealType.valueOf(rs.getString("meal_type")),
            takenAt = rs.instant("taken_at"),
            imageKey = rs.getString("image_key"),
            thumbKey = rs.getString("thumb_key"),
            grade = rs.getString("grade")?.let { Grade.valueOf(it) },
        )
    }

    fun feed(viewerId: Long, roomId: Long, cursor: String?, size: Int): FeedResponse {
        roomService.requireMember(roomId, viewerId)
        val limit = size.coerceIn(1, 30)
        val after = cursor?.let(::decodeCursor)

        val sql = buildString {
            append(
                """
                SELECT s.meal_id, s.visible_at, m.user_id, u.nickname, m.meal_type, m.taken_at,
                       m.image_key, m.thumb_key, a.grade
                FROM meal_shares s
                JOIN meals m ON m.id = s.meal_id
                JOIN users u ON u.id = m.user_id
                LEFT JOIN meal_analyses a ON a.meal_id = m.id
                WHERE s.room_id = :roomId
                  AND s.visible_at IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM reports r
                      WHERE r.meal_id = s.meal_id AND r.room_id = s.room_id AND r.reporter_id = :viewerId
                  )
                  AND (SELECT count(*) FROM reports r WHERE r.meal_id = s.meal_id AND r.room_id = s.room_id) < :hideAfter
                """.trimIndent()
            )
            if (after != null) append("\n  AND (s.visible_at, s.meal_id) < (:cursorAt, :cursorId)")
            append("\nORDER BY s.visible_at DESC, s.meal_id DESC\nLIMIT :limit")
        }

        var spec = jdbc.sql(sql)
            .param("roomId", roomId)
            .param("viewerId", viewerId)
            .param("hideAfter", HIDE_AFTER_REPORTS)
            .param("limit", limit + 1)
        if (after != null) {
            spec = spec.param("cursorAt", after.first.toOdt()).param("cursorId", after.second)
        }
        val rows = spec.query(rowMapper).list()

        val page = rows.take(limit)
        val reactions = reactionsFor(roomId, viewerId, page.map { it.mealId })
        val items = page.map { row ->
            FeedItem(
                mealId = row.mealId,
                author = FeedAuthor(row.authorId, row.authorNickname),
                mealType = row.mealType,
                takenAt = row.takenAt,
                sharedAt = row.visibleAt,
                imageUrl = row.imageKey?.let(storage::presignView),
                thumbUrl = (row.thumbKey ?: row.imageKey)?.let(storage::presignView),
                grade = row.grade,
                reactions = reactions[row.mealId].orEmpty(),
                mine = row.authorId == viewerId,
            )
        }
        val nextCursor = if (rows.size > limit) page.last().let { encodeCursor(it.visibleAt, it.mealId) } else null
        return FeedResponse(items, nextCursor)
    }

    private fun reactionsFor(roomId: Long, viewerId: Long, mealIds: List<Long>): Map<Long, List<ReactionSummary>> {
        if (mealIds.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            SELECT meal_id, type, count(*) AS cnt, bool_or(user_id = :viewerId) AS mine
            FROM reactions
            WHERE room_id = :roomId AND meal_id IN (:ids)
            GROUP BY meal_id, type
            """.trimIndent()
        )
            .param("roomId", roomId)
            .param("viewerId", viewerId)
            .param("ids", mealIds)
            .query(RowMapper { rs, _ ->
                rs.getLong("meal_id") to ReactionSummary(
                    ReactionType.valueOf(rs.getString("type")),
                    rs.getInt("cnt"),
                    rs.getBoolean("mine"),
                )
            })
            .list()
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedBy { it.type.ordinal } }
    }

    private fun encodeCursor(at: Instant, mealId: Long): String {
        val micros = at.epochSecond * 1_000_000 + at.nano / 1_000
        return "${micros}_$mealId"
    }

    private fun decodeCursor(cursor: String): Pair<Instant, Long> {
        val parts = cursor.split("_")
        val micros = parts.getOrNull(0)?.toLongOrNull()
        val mealId = parts.getOrNull(1)?.toLongOrNull()
        if (parts.size != 2 || micros == null || mealId == null) {
            throw ApiException.badRequest("INVALID_CURSOR", "잘못된 페이지 정보예요")
        }
        val at = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000)
        return at to mealId
    }

    companion object {
        const val HIDE_AFTER_REPORTS = 2
    }
}
