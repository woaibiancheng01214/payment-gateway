package com.payment.merchant.controller

import com.payment.merchant.dto.MerchantExistsResponse
import com.payment.merchant.service.MerchantService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/internal/merchants")
class MerchantInternalController(
    private val merchantService: MerchantService
) {

    @GetMapping("/{id}/exists")
    fun exists(@PathVariable id: String): ResponseEntity<MerchantExistsResponse> {
        return ResponseEntity.ok(MerchantExistsResponse(exists = merchantService.merchantExists(id)))
    }
}
