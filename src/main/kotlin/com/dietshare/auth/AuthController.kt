package com.dietshare.auth

import com.dietshare.common.ApiException
import com.dietshare.common.AppProperties
import com.dietshare.user.UserResponse
import com.dietshare.user.UserService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class KakaoLoginRequest(@field:NotBlank val accessToken: String)

data class DevLoginRequest(@field:NotBlank @field:Size(max = 50) val deviceId: String)

data class LoginResponse(val token: String, val user: UserResponse, val isNewUser: Boolean)

@RestController
@RequestMapping("/auth")
class AuthController(
    private val kakaoClient: KakaoClient,
    private val userService: UserService,
    private val jwtProvider: JwtProvider,
    private val props: AppProperties,
) {
    @PostMapping("/kakao")
    fun kakao(@Valid @RequestBody req: KakaoLoginRequest): LoginResponse =
        login("KAKAO", kakaoClient.fetchUserId(req.accessToken))

    /** Beta-only login without Kakao. Enabled with DEV_LOGIN_ENABLED=true. */
    @PostMapping("/dev")
    fun dev(@Valid @RequestBody req: DevLoginRequest): LoginResponse {
        if (!props.auth.devLoginEnabled) throw ApiException.notFound("NOT_FOUND", "지원하지 않는 로그인이에요")
        return login("DEV", req.deviceId)
    }

    private fun login(provider: String, providerUserId: String): LoginResponse {
        val (user, isNew) = userService.findOrCreate(provider, providerUserId)
        return LoginResponse(jwtProvider.issue(user.id), UserResponse.from(user), isNew)
    }
}
