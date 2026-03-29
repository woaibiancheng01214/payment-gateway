package com.payment.merchant

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MerchantApplication

fun main(args: Array<String>) {
    runApplication<MerchantApplication>(*args)
}
