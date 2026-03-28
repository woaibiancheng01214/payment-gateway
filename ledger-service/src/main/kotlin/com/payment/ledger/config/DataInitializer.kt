package com.payment.ledger.config

import com.payment.ledger.entity.AccountType
import com.payment.ledger.entity.LedgerAccount
import com.payment.ledger.repository.LedgerAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class DataInitializer(
    private val accountRepository: LedgerAccountRepository
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        val accounts = listOf(
            "merchant_receivables" to AccountType.ASSET,
            "gateway_payable" to AccountType.LIABILITY,
            "merchant_revenue" to AccountType.REVENUE
        )
        for ((name, type) in accounts) {
            if (accountRepository.findByName(name) == null) {
                accountRepository.save(LedgerAccount(name = name, type = type))
                log.info("Created ledger account: $name ($type)")
            }
        }
    }
}
