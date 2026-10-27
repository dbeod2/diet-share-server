package com.dietshare.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class ErrorResponse(val code: String, val message: String)

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.status).body(ErrorResponse(e.code, e.message ?: e.code))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", message))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun handleUnreadable(e: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", "요청 형식이 올바르지 않아요"))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", "${e.parameterName} 값이 필요해요"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(e: NoResourceFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("NOT_FOUND", "없는 주소예요"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethod(e: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorResponse("METHOD_NOT_ALLOWED", "지원하지 않는 요청이에요"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled error", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_ERROR", "잠시 후 다시 시도해 주세요"))
    }
}
