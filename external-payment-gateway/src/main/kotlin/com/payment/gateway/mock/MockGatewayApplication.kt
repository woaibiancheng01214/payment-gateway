package com.payment.gateway.mock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MockGatewayApplication

fun main(args: Array<String>) {
    runApplication<MockGatewayApplication>(*args)
}
