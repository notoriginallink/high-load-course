package ru.quipy.payments.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.prometheus.metrics.core.metrics.Summary
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class PaymentMetrics(
    private val meterRegistry: MeterRegistry,
) {
    private companion object {
        const val SUCCESS_PAYMENT_TAG = "success"
        const val FAILED_PAYMENT_TAG = "failed"
        const val TIMEOUT_PAYMENT_TAG = "timeout"
        const val FAILED_RETRIABLE_PAYMENT_TAG = "failed_retryable"
    }

    private val outgoingRequestProcessingTime = DistributionSummary
        .builder("outgoing_request_processing_time")
        .description("Outgoing request latency")
        .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
        .publishPercentileHistogram()
        .register(meterRegistry)

    fun incIncomingTotal(url: String, httpStatus: HttpStatus = HttpStatus.OK) = Counter
        .builder("incoming_http_requests")
        .description("Number of incoming requests")
        .tags(
            "url", url,
            "response_code", httpStatus.value().toString(),
            "response_desc", httpStatus.reasonPhrase,
        )
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

    fun recordOutgoingRequest(processingTime: Long) = outgoingRequestProcessingTime.record(processingTime.toDouble())

    fun incSuccessPayment(account: String) = incPaymentsCount(account, SUCCESS_PAYMENT_TAG)

    fun incTimeoutPayment(account: String) = incPaymentsCount(account, TIMEOUT_PAYMENT_TAG)

    fun incFailedPayment(account: String) = incPaymentsCount(account, FAILED_PAYMENT_TAG)

    fun incFailedRetryablePayment(account: String) = incPaymentsCount(account, FAILED_RETRIABLE_PAYMENT_TAG)

    private fun incPaymentsCount(account: String, status: String) = Counter
        .builder("payments_count")
        .description("Number of payments")
        .tags(
            "account", account,
            "status", status,
        )
        .register(meterRegistry)
        .increment()
}