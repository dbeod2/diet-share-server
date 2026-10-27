package com.dietshare.auth

import com.dietshare.common.ApiException
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/** Verifies a Kakao access token obtained by the app and returns the Kakao user id. */
@Component
class KakaoClient(builder: RestClient.Builder) {
    private val client = builder.baseUrl("https://kapi.kakao.com").build()

    data class KakaoUser(val id: Long? = null)

    fun fetchUserId(accessToken: String): String {
        val user = try {
            client.get()
                .uri("/v2/user/me")
                .header("Authorization", "Bearer $accessToken")
                .retrieve()
                .body(KakaoUser::class.java)
        } catch (e: RestClientException) {
            throw ApiException.unauthorized("카카오 로그인 정보를 확인하지 못했어요")
        }
        return user?.id?.toString() ?: throw ApiException.unauthorized("카카오 로그인 정보를 확인하지 못했어요")
    }
}
