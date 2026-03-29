package com.payment.token.service

import com.payment.token.dto.PaymentMethodAuthResponse
import com.payment.token.dto.PaymentMethodBriefResponse
import com.payment.token.entity.CardBrand
import com.payment.token.entity.PaymentMethod
import com.payment.token.repository.PaymentMethodRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentMethodService(
    private val paymentMethodRepository: PaymentMethodRepository
) {

    @Transactional
    fun createPaymentMethod(
        customerId: String?,
        cardDataId: String,
        brand: CardBrand,
        last4: String,
        expMonth: Int,
        expYear: Int
    ): String {
        val paymentMethod = PaymentMethod(
            customerId = customerId,
            cardDataId = cardDataId,
            brand = brand,
            last4 = last4,
            expMonth = expMonth,
            expYear = expYear
        )
        paymentMethodRepository.save(paymentMethod)
        return paymentMethod.id
    }

    @Transactional(readOnly = true)
    fun getPaymentMethodBrief(id: String): PaymentMethodBriefResponse {
        val pm = paymentMethodRepository.findById(id)
            .orElseThrow { NoSuchElementException("Payment method not found: $id") }
        return PaymentMethodBriefResponse(
            id = pm.id,
            brand = pm.brand.name,
            last4 = pm.last4,
            expMonth = pm.expMonth,
            expYear = pm.expYear,
            status = pm.status.name
        )
    }

    @Transactional(readOnly = true)
    fun getPaymentMethodForAuth(id: String): PaymentMethodAuthResponse {
        val pm = paymentMethodRepository.findById(id)
            .orElseThrow { NoSuchElementException("Payment method not found: $id") }
        return PaymentMethodAuthResponse(
            cardDataId = pm.cardDataId,
            brand = pm.brand.name,
            expMonth = pm.expMonth,
            expYear = pm.expYear
        )
    }
}
