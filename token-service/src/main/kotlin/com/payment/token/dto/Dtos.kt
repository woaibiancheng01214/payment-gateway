package com.payment.token.dto

data class CreatePaymentMethodRequest(
    val customerId: String? = null,
    val cardDataId: String,
    val brand: String,
    val last4: String,
    val expMonth: Int,
    val expYear: Int
)

data class CreatePaymentMethodResponse(val paymentMethodId: String)

data class PaymentMethodBriefResponse(
    val id: String,
    val brand: String,
    val last4: String,
    val expMonth: Int,
    val expYear: Int,
    val status: String
)

data class PaymentMethodAuthResponse(
    val cardDataId: String,
    val brand: String,
    val expMonth: Int,
    val expYear: Int
)
