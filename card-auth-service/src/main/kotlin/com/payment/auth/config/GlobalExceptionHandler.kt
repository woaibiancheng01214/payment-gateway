package com.payment.auth.config

import com.payment.auth.dto.ApiError
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ApiError> {
        log.warn("Bad request: ${ex.message}")
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(
                type = "invalid_request_error",
                code = "invalid_argument",
                message = ex.message ?: "Invalid argument"
            )
        )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): ResponseEntity<ApiError> {
        log.error("Internal error: ${ex.message}")
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiError(
                type = "api_error",
                code = "internal_error",
                message = ex.message ?: "Internal server error"
            )
        )
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ApiError> {
        log.warn("Not found: ${ex.message}")
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(
                type = "invalid_request_error",
                code = "not_found",
                message = ex.message ?: "Resource not found"
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled exception: ${ex.message}", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiError(
                type = "api_error",
                code = "internal_error",
                message = "An unexpected error occurred"
            )
        )
    }
}
