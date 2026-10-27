package com.dietshare.common

import org.springframework.http.HttpStatus

class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
) : RuntimeException(message) {
    companion object {
        fun badRequest(code: String, message: String) = ApiException(HttpStatus.BAD_REQUEST, code, message)
        fun unauthorized(message: String = "로그인이 필요해요") = ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message)
        fun forbidden(code: String = "FORBIDDEN", message: String = "권한이 없어요") = ApiException(HttpStatus.FORBIDDEN, code, message)
        fun notFound(code: String, message: String) = ApiException(HttpStatus.NOT_FOUND, code, message)
        fun conflict(code: String, message: String) = ApiException(HttpStatus.CONFLICT, code, message)
        fun tooMany(code: String, message: String) = ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message)
    }
}
