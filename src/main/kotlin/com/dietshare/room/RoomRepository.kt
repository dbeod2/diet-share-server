package com.dietshare.room

import com.dietshare.common.instant
import com.dietshare.common.instantOrNull
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

enum class RoomRole { OWNER, MEMBER }

data class Room(
    val id: Long,
    val name: String,
    val ownerId: Long,
    val inviteCode: String,
    val memberCount: Int,
    val maxMembers: Int,
    val createdAt: Instant,
)

data class RoomSummary(
    val room: Room,
    val myRole: RoomRole,
    val lastSharedAt: Instant?,
    val coverKeys: List<String>,
)

data class RoomMember(val userId: Long, val nickname: String, val role: RoomRole, val joinedAt: Instant)

@Repository
class RoomRepository(private val jdbc: JdbcClient) {
    private val roomMapper = RowMapper { rs, _ ->
        Room(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            ownerId = rs.getLong("owner_id"),
            inviteCode = rs.getString("invite_code"),
            memberCount = rs.getInt("member_count"),
            maxMembers = rs.getInt("max_members"),
            createdAt = rs.instant("created_at"),
        )
    }

    fun insert(name: String, ownerId: Long, inviteCode: String, maxMembers: Int): Long =
        jdbc.sql(
            """
            INSERT INTO rooms (name, owner_id, invite_code, member_count, max_members)
            VALUES (:name, :ownerId, :code, 0, :max)
            RETURNING id
            """.trimIndent()
        )
            .param("name", name)
            .param("ownerId", ownerId)
            .param("code", inviteCode)
            .param("max", maxMembers)
            .query(java.lang.Long::class.java).single().toLong()

    fun findById(id: Long): Room? =
        jdbc.sql("SELECT * FROM rooms WHERE id = :id").param("id", id)
            .query(roomMapper).optional().orElse(null)

    fun findByCode(code: String): Room? =
        jdbc.sql("SELECT * FROM rooms WHERE invite_code = :code").param("code", code)
            .query(roomMapper).optional().orElse(null)

    fun lockById(id: Long): Room? =
        jdbc.sql("SELECT * FROM rooms WHERE id = :id FOR UPDATE").param("id", id)
            .query(roomMapper).optional().orElse(null)

    /** Returns false when the user is already a member. */
    fun insertMember(roomId: Long, userId: Long, role: RoomRole): Boolean =
        jdbc.sql(
            """
            INSERT INTO room_members (room_id, user_id, role) VALUES (:roomId, :userId, :role)
            ON CONFLICT (room_id, user_id) DO NOTHING
            """.trimIndent()
        )
            .param("roomId", roomId)
            .param("userId", userId)
            .param("role", role.name)
            .update() == 1

    /** Takes a seat only if one is free. The row lock makes concurrent joins line up. */
    fun takeSeat(roomId: Long): Boolean =
        jdbc.sql(
            """
            UPDATE rooms SET member_count = member_count + 1
            WHERE id = :roomId AND member_count < max_members
            """.trimIndent()
        ).param("roomId", roomId).update() == 1

    fun releaseSeat(roomId: Long) {
        jdbc.sql("UPDATE rooms SET member_count = member_count - 1 WHERE id = :roomId AND member_count > 0")
            .param("roomId", roomId).update()
    }

    fun deleteMember(roomId: Long, userId: Long): Boolean =
        jdbc.sql("DELETE FROM room_members WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .update() == 1

    fun roleOf(roomId: Long, userId: Long): RoomRole? =
        jdbc.sql("SELECT role FROM room_members WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(String::class.java).optional().map { RoomRole.valueOf(it) }.orElse(null)

    /** Of [roomIds], the ones the user belongs to. */
    fun memberRoomIds(userId: Long, roomIds: Collection<Long>): Set<Long> {
        if (roomIds.isEmpty()) return emptySet()
        return jdbc.sql("SELECT room_id FROM room_members WHERE user_id = :userId AND room_id IN (:ids)")
            .param("userId", userId)
            .param("ids", roomIds)
            .query(java.lang.Long::class.java).list().map { it.toLong() }.toSet()
    }

    fun roomIdsOfUser(userId: Long): List<Long> =
        jdbc.sql("SELECT room_id FROM room_members WHERE user_id = :userId")
            .param("userId", userId)
            .query(java.lang.Long::class.java).list().map { it.toLong() }

    fun summariesOfUser(userId: Long): List<RoomSummary> =
        jdbc.sql(
            """
            SELECT r.*, rm.role AS my_role,
                   (SELECT max(s.visible_at) FROM meal_shares s WHERE s.room_id = r.id) AS last_shared_at,
                   ARRAY(
                       SELECT COALESCE(m.thumb_key, m.image_key)
                       FROM meal_shares s JOIN meals m ON m.id = s.meal_id
                       WHERE s.room_id = r.id AND s.visible_at IS NOT NULL
                       ORDER BY s.visible_at DESC
                       LIMIT 4
                   ) AS cover_keys
            FROM rooms r
            JOIN room_members rm ON rm.room_id = r.id AND rm.user_id = :userId
            ORDER BY last_shared_at DESC NULLS LAST, r.id DESC
            """.trimIndent()
        )
            .param("userId", userId)
            .query(RowMapper { rs, i ->
                @Suppress("UNCHECKED_CAST")
                val covers = (rs.getArray("cover_keys")?.array as Array<String>?)?.toList() ?: emptyList()
                RoomSummary(
                    room = roomMapper.mapRow(rs, i)!!,
                    myRole = RoomRole.valueOf(rs.getString("my_role")),
                    lastSharedAt = rs.instantOrNull("last_shared_at"),
                    coverKeys = covers,
                )
            }).list()

    fun members(roomId: Long): List<RoomMember> =
        jdbc.sql(
            """
            SELECT rm.user_id, u.nickname, rm.role, rm.joined_at
            FROM room_members rm JOIN users u ON u.id = rm.user_id
            WHERE rm.room_id = :roomId
            ORDER BY rm.joined_at, rm.user_id
            """.trimIndent()
        )
            .param("roomId", roomId)
            .query(RowMapper { rs, _ ->
                RoomMember(
                    userId = rs.getLong("user_id"),
                    nickname = rs.getString("nickname"),
                    role = RoomRole.valueOf(rs.getString("role")),
                    joinedAt = rs.instant("joined_at"),
                )
            }).list()

    fun transferOwnership(roomId: Long, newOwnerId: Long) {
        jdbc.sql("UPDATE rooms SET owner_id = :userId WHERE id = :roomId")
            .param("roomId", roomId).param("userId", newOwnerId).update()
        jdbc.sql("UPDATE room_members SET role = 'OWNER' WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId).param("userId", newOwnerId).update()
    }

    fun rename(roomId: Long, name: String) {
        jdbc.sql("UPDATE rooms SET name = :name WHERE id = :roomId")
            .param("roomId", roomId).param("name", name).update()
    }

    fun updateInviteCode(roomId: Long, code: String) {
        jdbc.sql("UPDATE rooms SET invite_code = :code WHERE id = :roomId")
            .param("roomId", roomId).param("code", code).update()
    }

    fun delete(roomId: Long) {
        jdbc.sql("DELETE FROM rooms WHERE id = :roomId").param("roomId", roomId).update()
    }
}
