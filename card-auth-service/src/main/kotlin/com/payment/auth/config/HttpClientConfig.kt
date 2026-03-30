package com.payment.auth.config

import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.apache.hc.core5.util.Timeout
import org.slf4j.MDC
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import java.util.function.Supplier

@Configuration
class HttpClientConfig {

    @Bean
    fun restTemplateBuilder(): RestTemplateBuilder {
        val connectionManager = PoolingHttpClientConnectionManager().apply {
            maxTotal = 100
            defaultMaxPerRoute = 50
        }
        val requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(5))
            .setResponseTimeout(Timeout.ofSeconds(10))
            .build()
        val httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .build()
        val factory = HttpComponentsClientHttpRequestFactory(httpClient)
        val correlationInterceptor = ClientHttpRequestInterceptor { request, body, execution ->
            MDC.get(CorrelationIdFilter.MDC_KEY)?.let {
                request.headers.set(CorrelationIdFilter.HEADER_NAME, it)
            }
            execution.execute(request, body)
        }
        return RestTemplateBuilder()
            .requestFactory(Supplier { factory })
            .additionalInterceptors(listOf(correlationInterceptor))
    }
}
