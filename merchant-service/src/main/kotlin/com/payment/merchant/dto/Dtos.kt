package com.payment.merchant.dto

import com.payment.merchant.entity.Merchant
import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class CreateMerchantRequest(
    @field:NotBlank(message = "Merchant name is required")
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
