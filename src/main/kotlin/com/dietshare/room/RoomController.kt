package com.dietshare.room

import com.dietshare.analysis.Grade
import com.dietshare.auth.CurrentUser
import com.dietshare.meal.MealType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// ---- Requests -----------------------------------------------------------

data class RoomNameRequest(@field:NotBlank @field:Size(max = 20) val name: String)

data class JoinRoomRequest(@field:NotBlank @field:Size(max = 12) val code: String)

data class ReactionRequest(val type: ReactionType)

data class ReportRequest(val reason: ReportReason)

// ---- Responses ----------------------------------------------------------

data class RoomListItem(
    val id: Long,
    val name: String,
    val memberCount: Int,
    val maxMembers: Int,
    val myRole: RoomRole,
    val lastSharedAt: Instant?,
    val coverUrls: List<String>,
)

data class MemberResponse(val userId: Long, val nickname: String, val role: RoomRole)

data class RoomDetailResponse(
    val id: Long,
    val name: String,
    val inviteCode: String,
    val memberCount: Int,
    val maxMembers: Int,
    val myRole: RoomRole,
    val members: List<MemberResponse>,
)

data class RoomPreviewResponse(val name: String, val memberCount: Int, val maxMembers: Int, val alreadyJoined: Boolean)

data class FeedAuthor(val userId: Long, val nickname: String)

data class ReactionSummary(val type: ReactionType, val count: Int, val mine: Boolean)

data class FeedItem(
    val mealId: Long,
    val author: FeedAuthor,
    val mealType: MealType,
    val takenAt: Instant,
    val sharedAt: Instant,
    val imageUrl: String?,
    val thumbUrl: String?,
    val grade: Grade?,
    val reactions: List<ReactionSummary>,
    val mine: Boolean,
)

data class FeedResponse(val items: List<FeedItem>, val nextCursor: String?)

data class ReactorResponse(val userId: Long, val nickname: String, val type: ReactionType)

// ---- Endpoints ----------------------------------------------------------

@RestController
@RequestMapping("/rooms")
class RoomController(
    private val roomService: RoomService,
    private val feedService: FeedService,
    private val socialService: SocialService,
) {
    @PostMapping
    fun create(@CurrentUser userId: Long, @Valid @RequestBody req: RoomNameRequest) = roomService.create(userId, req.name)

    @GetMapping
    fun list(@CurrentUser userId: Long) = roomService.list(userId)

    @GetMapping("/preview")
    fun preview(@CurrentUser userId: Long, @RequestParam code: String) = roomService.preview(userId, code)

    @PostMapping("/join")
    fun join(@CurrentUser userId: Long, @Valid @RequestBody req: JoinRoomRequest) = roomService.join(userId, req.code)

    @GetMapping("/{roomId}")
    fun detail(@CurrentUser userId: Long, @PathVariable roomId: Long) = roomService.detail(userId, roomId)

    @PatchMapping("/{roomId}")
    fun rename(@CurrentUser userId: Long, @PathVariable roomId: Long, @Valid @RequestBody req: RoomNameRequest) =
        roomService.rename(userId, roomId, req.name)

    @PostMapping("/{roomId}/invite-code")
    fun reissueInviteCode(@CurrentUser userId: Long, @PathVariable roomId: Long) =
        roomService.reissueInviteCode(userId, roomId)

    @PostMapping("/{roomId}/leave")
    fun leave(@CurrentUser userId: Long, @PathVariable roomId: Long): ResponseEntity<Unit> {
        roomService.leave(userId, roomId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{roomId}/members/{memberId}")
    fun kick(@CurrentUser userId: Long, @PathVariable roomId: Long, @PathVariable memberId: Long): ResponseEntity<Unit> {
        roomService.kick(userId, roomId, memberId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{roomId}/feed")
    fun feed(
        @CurrentUser userId: Long,
        @PathVariable roomId: Long,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "10") size: Int,
    ) = feedService.feed(userId, roomId, cursor, size)

    @PostMapping("/{roomId}/meals/{mealId}/reactions")
    fun react(
        @CurrentUser userId: Long,
        @PathVariable roomId: Long,
        @PathVariable mealId: Long,
        @RequestBody req: ReactionRequest,
    ): ResponseEntity<Unit> {
        socialService.react(userId, roomId, mealId, req.type)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{roomId}/meals/{mealId}/reactions/{type}")
    fun unreact(
        @CurrentUser userId: Long,
        @PathVariable roomId: Long,
        @PathVariable mealId: Long,
        @PathVariable type: ReactionType,
    ): ResponseEntity<Unit> {
        socialService.unreact(userId, roomId, mealId, type)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{roomId}/meals/{mealId}/reactions")
    fun reactors(@CurrentUser userId: Long, @PathVariable roomId: Long, @PathVariable mealId: Long) =
        socialService.reactors(userId, roomId, mealId)

    @PostMapping("/{roomId}/meals/{mealId}/reports")
    fun report(
        @CurrentUser userId: Long,
        @PathVariable roomId: Long,
        @PathVariable mealId: Long,
        @RequestBody req: ReportRequest,
    ): ResponseEntity<Unit> {
        socialService.report(userId, roomId, mealId, req.reason)
        return ResponseEntity.noContent().build()
    }
}
