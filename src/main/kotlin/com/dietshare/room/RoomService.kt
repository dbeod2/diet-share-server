package com.dietshare.room

import com.dietshare.common.ApiException
import com.dietshare.common.AppProperties
import com.dietshare.quota.JoinRateLimiter
import com.dietshare.storage.StorageService
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom

@Service
class RoomService(
    private val rooms: RoomRepository,
    private val shares: ShareRepository,
    private val rateLimiter: JoinRateLimiter,
    private val storage: StorageService,
    private val tx: TransactionTemplate,
    private val props: AppProperties,
) {
    private val random = SecureRandom()

    fun create(userId: Long, name: String): RoomDetailResponse {
        repeat(5) {
            try {
                val roomId = tx.execute {
                    val id = rooms.insert(name.trim(), userId, newInviteCode(), props.room.maxMembers)
                    rooms.insertMember(id, userId, RoomRole.OWNER)
                    rooms.takeSeat(id)
                    id
                }!!
                return detail(userId, roomId)
            } catch (e: DuplicateKeyException) {
                // Invite code collision: try another code.
            }
        }
        throw IllegalStateException("Could not generate a unique invite code")
    }

    fun list(userId: Long): List<RoomListItem> =
        rooms.summariesOfUser(userId).map { s ->
            RoomListItem(
                id = s.room.id,
                name = s.room.name,
                memberCount = s.room.memberCount,
                maxMembers = s.room.maxMembers,
                myRole = s.myRole,
                lastSharedAt = s.lastSharedAt,
                coverUrls = s.coverKeys.map(storage::presignView),
            )
        }

    fun detail(userId: Long, roomId: Long): RoomDetailResponse {
        val room = rooms.findById(roomId) ?: throw roomNotFound()
        val myRole = rooms.roleOf(roomId, userId) ?: throw roomNotFound()
        return RoomDetailResponse(
            id = room.id,
            name = room.name,
            inviteCode = room.inviteCode,
            memberCount = room.memberCount,
            maxMembers = room.maxMembers,
            myRole = myRole,
            members = rooms.members(roomId).map { MemberResponse(it.userId, it.nickname, it.role) },
        )
    }

    /** Shown before joining so the user can confirm the room. */
    fun preview(userId: Long, code: String): RoomPreviewResponse {
        rateLimiter.check(userId)
        val room = rooms.findByCode(normalize(code)) ?: throw invalidCode()
        return RoomPreviewResponse(room.name, room.memberCount, room.maxMembers, rooms.roleOf(room.id, userId) != null)
    }

    /**
     * Seat check and membership insert happen in one transaction.
     * The conditional UPDATE on rooms.member_count serialises concurrent joins on the row lock,
     * so a 5-seat room never ends up with a 6th member.
     */
    fun join(userId: Long, code: String): RoomDetailResponse {
        rateLimiter.check(userId)
        val room = rooms.findByCode(normalize(code)) ?: throw invalidCode()
        tx.executeWithoutResult {
            val inserted = rooms.insertMember(room.id, userId, RoomRole.MEMBER)
            if (inserted && !rooms.takeSeat(room.id)) {
                throw ApiException.conflict("ROOM_FULL", "방이 꽉 찼어요 (${room.maxMembers}/${room.maxMembers})")
            }
        }
        return detail(userId, room.id)
    }

    fun leave(userId: Long, roomId: Long) {
        tx.executeWithoutResult {
            val room = rooms.lockById(roomId) ?: throw roomNotFound()
            val role = rooms.roleOf(roomId, userId) ?: throw roomNotFound()
            removeMember(roomId, userId)
            if (role == RoomRole.OWNER) {
                // The earliest remaining member takes over; an empty room is deleted.
                val next = rooms.members(room.id).firstOrNull()
                if (next == null) rooms.delete(room.id) else rooms.transferOwnership(room.id, next.userId)
            }
        }
    }

    fun leaveAllRooms(userId: Long) {
        rooms.roomIdsOfUser(userId).forEach { leave(userId, it) }
    }

    fun kick(ownerId: Long, roomId: Long, targetUserId: Long) {
        if (ownerId == targetUserId) throw ApiException.badRequest("CANNOT_KICK_SELF", "방장은 스스로 내보낼 수 없어요")
        tx.executeWithoutResult {
            rooms.lockById(roomId) ?: throw roomNotFound()
            requireOwner(roomId, ownerId)
            if (rooms.roleOf(roomId, targetUserId) == null) {
                throw ApiException.notFound("MEMBER_NOT_FOUND", "방에 없는 멤버예요")
            }
            removeMember(roomId, targetUserId)
        }
    }

    fun rename(userId: Long, roomId: Long, name: String): RoomDetailResponse {
        requireOwner(roomId, userId)
        rooms.rename(roomId, name.trim())
        return detail(userId, roomId)
    }

    /** The previous code stops working immediately. */
    fun reissueInviteCode(userId: Long, roomId: Long): RoomDetailResponse {
        requireOwner(roomId, userId)
        repeat(5) {
            try {
                rooms.updateInviteCode(roomId, newInviteCode())
                return detail(userId, roomId)
            } catch (e: DuplicateKeyException) {
                // collision, retry
            }
        }
        throw IllegalStateException("Could not generate a unique invite code")
    }

    fun requireMember(roomId: Long, userId: Long): RoomRole =
        rooms.roleOf(roomId, userId) ?: throw roomNotFound()

    /** The member's meals leave the room with them; their reactions there are removed too. */
    private fun removeMember(roomId: Long, userId: Long) {
        shares.deleteUserSharesInRoom(userId, roomId)
        shares.deleteUserReactionsInRoom(userId, roomId)
        if (rooms.deleteMember(roomId, userId)) rooms.releaseSeat(roomId)
    }

    private fun requireOwner(roomId: Long, userId: Long) {
        if (rooms.roleOf(roomId, userId) != RoomRole.OWNER) throw ApiException.forbidden("OWNER_ONLY", "방장만 할 수 있어요")
    }

    /** 8 characters without look-alikes (0/O, 1/I). */
    private fun newInviteCode(): String =
        (1..8).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    private fun normalize(code: String) = code.trim().replace(" ", "").uppercase()

    private fun roomNotFound() = ApiException.notFound("ROOM_NOT_FOUND", "방을 찾을 수 없어요")

    private fun invalidCode() = ApiException.notFound("INVALID_INVITE_CODE", "유효하지 않은 코드예요")

    companion object {
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
