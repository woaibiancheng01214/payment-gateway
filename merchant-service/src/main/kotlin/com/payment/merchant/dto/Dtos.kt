package com.payment.merchant.dto

import com.payment.merchant.entity.Merchant
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateMerchantRequest(
    @field:NotBlank(message = "Merchant name is required")
    @field:Size(min = 1, max = 200, message = "Merchant name must be between 1 and 200 characters")
    val name: String
)

data class MerchantResponse(
    val id: String,
    val name: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class MerchantExistsResponse(
    val exists: Boolean
)

fun Merchant.toResponse() = MerchantResponse(
    id = id,
    name = name,
    status = status.name.lowercase(),
    createdAt = createdAt,
    updatedAt = updatedAt
)
