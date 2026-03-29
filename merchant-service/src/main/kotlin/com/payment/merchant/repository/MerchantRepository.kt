package com.payment.merchant.repository

import com.payment.merchant.entity.Merchant
import org.springframework.data.jpa.repository.JpaRepository

interface MerchantRepository : JpaRepository<Merchant, String>
