package com.payment.auth.config

import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.slf4j.MDC
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import java.time.Duration
import java.util.function.Supplier

@Configuration
class HttpClientConfig {

    @Bean
    fun pooledRestTemplate(builder: RestTemplateBuilder): RestTemplateBuilder {
        val connectionManager = PoolingHttpClientConnectionManager().apply {
            maxTotal = 100
            defaultMaxPerRoute = 50
        }
        val httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .build()
        val factory = HttpComponentsClientHttpRequestFactory(httpClient).apply {
            setConnectTimeout(Duration.ofSeconds(5))
        }
        val correlationInterceptor = ClientHttpRequestInterceptor { request, body, execution ->
            MDC.get(CorrelationIdFilter.MDC_KEY)?.let {
                request.headers.set(CorrelationIdFilter.HEADER_NAME, it)
            }
            execution.execute(request, body)
        }
        return builder
            .requestFactory(Supplier { factory })
            .additionalInterceptors(listOf(correlationInterceptor))
    }
}
