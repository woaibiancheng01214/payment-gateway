package com.payment.merchant.config

import com.payment.merchant.dto.ApiError
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            ApiError(
                type = ApiError.TYPE_INVALID_REQUEST,
                code = ApiError.CODE_INVALID_PARAM,
                message = e.message ?: "Bad request"
            )
        )

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(
                type = ApiError.TYPE_INVALID_REQUEST,
                code = ApiError.CODE_RESOURCE_NOT_FOUND,
                message = e.message ?: "Not found"
            )
        )

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(e: IllegalStateException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiError(
                type = ApiError.TYPE_CONFLICT,
                code = ApiError.CODE_STATE_CONFLICT,
                message = e.message ?: "Conflict"
            )
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val firstError = e.bindingResult.fieldErrors.firstOrNull()
        return ResponseEntity.badRequest().body(
            ApiError(
                type = ApiError.TYPE_INVALID_REQUEST,
                code = ApiError.CODE_INVALID_PARAM,
                message = firstError?.defaultMessage ?: "Validation failed",
                param = firstError?.field
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled exception: ${e.message}", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiError(
                type = ApiError.TYPE_API_ERROR,
                code = ApiError.CODE_SERVICE_UNAVAILABLE,
                message = "Internal server error"
            )
        )
    }
}
