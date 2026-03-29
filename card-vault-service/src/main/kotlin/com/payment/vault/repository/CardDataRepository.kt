package com.payment.vault.repository

import com.payment.vault.entity.CardData
import org.springframework.data.jpa.repository.JpaRepository

interface CardDataRepository : JpaRepository<CardData, String>
