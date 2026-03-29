package com.payment.merchant.controller

import com.payment.merchant.dto.CreateMerchantRequest
import com.payment.merchant.dto.MerchantResponse
import com.payment.merchant.service.MerchantService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/merchants")
class MerchantController(
    private val merchantService: MerchantService
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateMerchantRequest
    ): ResponseEntity<MerchantResponse> {
        val response = merchantService.createMerchant(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ResponseEntity<MerchantResponse> {
        return ResponseEntity.ok(merchantService.getMerchant(id))
    }

    @GetMapping
    fun list(@PageableDefault(size = 20) pageable: Pageable): ResponseEntity<Page<MerchantResponse>> {
        return ResponseEntity.ok(merchantService.listMerchants(pageable))
    }
}
