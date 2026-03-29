package com.payment.vault.controller

import com.payment.vault.dto.CardDataResponse
import com.payment.vault.dto.CreateCardDataRequest
import com.payment.vault.dto.CreateCardDataResponse
import com.payment.vault.service.VaultService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/card-data")
class VaultInternalController(
    private val vaultService: VaultService
) {
    @PostMapping
    fun createCardData(@Valid @RequestBody request: CreateCardDataRequest): ResponseEntity<CreateCardDataResponse> {
        val cardDataId = vaultService.createCardData(
            pan = request.pan,
            expMonth = request.expMonth,
            expYear = request.expYear,
            cardholderName = request.cardholderName
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateCardDataResponse(cardDataId))
    }

    @GetMapping("/{id}")
    fun getCardData(@PathVariable id: String): ResponseEntity<CardDataResponse> {
        val response = vaultService.getCardData(id)
        return ResponseEntity.ok(response)
    }
}
