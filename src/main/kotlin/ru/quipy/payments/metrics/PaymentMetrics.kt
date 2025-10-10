package ru.quipy.payments.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

@Service
class PaymentMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun incIncomingTotal(url: String) = Counter
        .builder("incoming_http_requests")
        .description("Number of incoming requests")
        .tags("url", url)
        .register(meterRegistry)
        .increment()

    fun incIncoming(account: String) = Counter
        .builder("payment_external_incoming_http_requests")
        .description("Number of incoming requests")
        .tags("account", account)
        .register(meterRegistry)
        .increment()

    fun incOutgoing(
        account: String,
        responseCode: String,
        responseDesc: String,
    ) = Counter
        .builder("payment_external_outgoing_http_requests")
        .description("Number of outgoing requests")
        .tags(
            "account", account,
            "response_code", responseCode,
            "response_desc", responseDesc,
        )
        .register(meterRegistry)
        .increment()
}