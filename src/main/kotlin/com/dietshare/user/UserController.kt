package com.dietshare.user

import com.dietshare.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

data class UserResponse(val id: Long, val nickname: String, val goalType: GoalType) {
    companion object {
        fun from(user: User) = UserResponse(user.id, user.nickname, user.goalType)
    }
}

data class UpdateMeRequest(
    @field:Size(min = 2, max = 12)
    @field:Pattern(regexp = "^[가-힣a-zA-Z0-9]+$", message = "한글, 영문, 숫자만 쓸 수 있어요")
    val nickname: String? = null,
    val goalType: GoalType? = null,
)

data class ProfileRequest(
    @field:Size(max = 10) val likedFoods: List<String> = emptyList(),
    @field:Size(max = 10) val dislikedFoods: List<String> = emptyList(),
    @field:Size(max = 20) val dietType: String? = null,
    @field:Min(50) @field:Max(250) val heightCm: Int? = null,
    @field:DecimalMin("20.0") @field:DecimalMax("300.0") val weightKg: BigDecimal? = null,
) {
    fun toProfile() = UserProfile(likedFoods, dislikedFoods, dietType, heightCm, weightKg)
}

@RestController
@RequestMapping("/me")
class UserController(private val userService: UserService) {
    @GetMapping
    fun me(@CurrentUser userId: Long) = UserResponse.from(userService.get(userId))

    @PatchMapping
    fun update(@CurrentUser userId: Long, @Valid @RequestBody req: UpdateMeRequest) =
        UserResponse.from(userService.update(userId, req.nickname, req.goalType))

    @DeleteMapping
    fun delete(@CurrentUser userId: Long): ResponseEntity<Unit> {
        userService.delete(userId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/profile")
    fun profile(@CurrentUser userId: Long) = userService.profile(userId)

    @PutMapping("/profile")
    fun updateProfile(@CurrentUser userId: Long, @Valid @RequestBody req: ProfileRequest) =
        userService.updateProfile(userId, req.toProfile())
}
