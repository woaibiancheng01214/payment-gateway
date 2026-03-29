package com.payment.merchant.service

import com.payment.merchant.dto.CreateMerchantRequest
import com.payment.merchant.dto.MerchantResponse
import com.payment.merchant.dto.toResponse
import com.payment.merchant.entity.Merchant
import com.payment.merchant.repository.MerchantRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MerchantService(
    private val merchantRepository: MerchantRepository
) {

    @Transactional
    fun createMerchant(request: CreateMerchantRequest): MerchantResponse {
        val merchant = Merchant(name = request.name)
        merchantRepository.save(merchant)
        return merchant.toResponse()
    }

    @Transactional(readOnly = true)
    fun getMerchant(id: String): MerchantResponse {
        val merchant = merchantRepository.findById(id)
            .orElseThrow { NoSuchElementException("Merchant not found: $id") }
        return merchant.toResponse()
    }

    @Transactional(readOnly = true)
    fun merchantExists(id: String): Boolean {
        return merchantRepository.existsById(id)
    }

    @Transactional(readOnly = true)
    fun listMerchants(pageable: Pageable): Page<MerchantResponse> {
        return merchantRepository.findAll(pageable).map { it.toResponse() }
    }
}
