package com.payment.auth

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class AuthApplication

fun main(args: Array<String>) {
    runApplication<AuthApplication>(*args)
}
