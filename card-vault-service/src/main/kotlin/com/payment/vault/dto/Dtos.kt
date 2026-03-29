package com.payment.vault.dto

data class CreateCardDataRequest(
    val pan: String,
    val expMonth: Int,
    val expYear: Int,
    val cardholderName: String? = null
)

data class CreateCardDataResponse(val cardDataId: String)

data class CardDataResponse(
    val pan: String,
    val expMonth: Int,
    val expYear: Int
)
