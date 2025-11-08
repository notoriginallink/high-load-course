package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.metrics.PaymentMetrics
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val metrics: PaymentMetrics,
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
        
        fun now() = System.currentTimeMillis()

        const val MAX_RETRIES: Int = 3
        const val RETRY_DELAY_MS: Long = 100
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
    private val rateLimiter = SlidingWindowRateLimiter(
        rate = rateLimitPerSec,
        window = Duration.ofSeconds(1),
    )
    private val ongoingWindow = OngoingWindow(maxWinSize = parallelRequests)

    private val client = OkHttpClient.Builder()
        .callTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.info("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()
        val currentTime = now()
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, currentTime, Duration.ofMillis(currentTime - paymentStartedAt))
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")
        if (isPaymentExpiredAt(deadline)) {
            paymentESService.update(paymentId) {
                it.logProcessing(success = false, currentTime, transactionId = transactionId, reason = "Deadline")
            }
            metrics.incTimeoutPayment(account = accountName)
            return
        }

        val timeToWait = Duration.ofMillis(deadline - currentTime - requestAverageProcessingTime.toMillis())
        if (!ongoingWindow.tryAcquire(timeToWait)) {
            paymentESService.update(paymentId) {
                it.logProcessing(success = false, now(), transactionId = transactionId, reason = "Deadline")
            }
            metrics.incTimeoutPayment(account = accountName)
            return
        }

        performExternalRequest(
            paymentId = paymentId,
            amount = amount,
            transactionId = transactionId,
            deadline = deadline,
        )

        ongoingWindow.release()
    }

    private fun performExternalRequest(
        paymentId: UUID,
        amount: Int,
        transactionId: UUID,
        deadline: Long,
        attempt: Int = 0
    ) {
        rateLimiter.tickBlocking()

        if (isPaymentExpiredAt(deadline)) {
            paymentESService.update(paymentId) {
                it.logProcessing(success = false, now(), transactionId = transactionId, reason = "Deadline")
            }
            metrics.incTimeoutPayment(account = accountName)
            return
        }

        val request = Request.Builder().run {
            url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
            post(emptyBody)
        }.build()

        val startTime = now()
        val response = try {
            client.newCall(request).execute().use { response ->
                metrics.recordOutgoingRequest(now() - startTime)
                metrics.incOutgoing(
                    account = accountName,
                    responseCode = response.code.toString(),
                    responseDesc = response.message,
                )
                return@use try {
                    mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                } catch (e: Exception) {
                    logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                    metrics.incFailedPayment(account = accountName)
                    ExternalSysResponse(transactionId.toString(), paymentId.toString(),false, e.message)
                }
            }
        } catch (e : SocketTimeoutException) {
            metrics.incOutgoing(
                account = accountName,
                responseCode = HttpStatus.REQUEST_TIMEOUT.value().toString(),
                responseDesc = HttpStatus.REQUEST_TIMEOUT.reasonPhrase,
            )
            logger.warn("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
            }
            return
        } catch (e : InterruptedIOException) {
            metrics.incOutgoing(
                account = accountName,
                responseCode = HttpStatus.REQUEST_TIMEOUT.value().toString(),
                responseDesc = HttpStatus.REQUEST_TIMEOUT.reasonPhrase,
            )
            logger.warn("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
            }
            metrics.incFailedRetryablePayment(account = accountName)
            if (attempt <= MAX_RETRIES) {
                val nextDelay = RETRY_DELAY_MS * (attempt + 1)
                if (isPaymentExpiredAt(now() + nextDelay)) {
                    paymentESService.update(paymentId) {
                        it.logProcessing(success = false, now(), transactionId = transactionId, reason = "Deadline")
                    }
                    metrics.incTimeoutPayment(account = accountName)
                    return
                }
                return performExternalRequest(
                    paymentId = paymentId,
                    amount = amount,
                    transactionId = transactionId,
                    deadline = deadline,
                    attempt = attempt + 1,
                )
            }
            return
        } catch (e : Exception) {
            logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)
            metrics.incFailedPayment(account = accountName)
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = e.message)
            }
            return
        }

        logger.info("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${response.result}, message: ${response.message}")

        when {
            response.isSuccessful() -> metrics.incSuccessPayment(account = accountName)
            response.isRetryable() && attempt <= MAX_RETRIES -> {
                metrics.incFailedRetryablePayment(account = accountName)
                val nextDelay = RETRY_DELAY_MS * (attempt + 1)
                if (isPaymentExpiredAt(now() + nextDelay)) {
                    paymentESService.update(paymentId) {
                        it.logProcessing(success = false, now(), transactionId = transactionId, reason = "Deadline")
                    }
                    metrics.incTimeoutPayment(account = accountName)
                    return
                }
                Thread.sleep(nextDelay)
                performExternalRequest(
                    paymentId = paymentId,
                    amount = amount,
                    transactionId = transactionId,
                    deadline = deadline,
                    attempt = attempt + 1,
                )
            }
            else -> metrics.incFailedPayment(account = accountName)
        }

        paymentESService.update(paymentId) {
            it.logProcessing(response.result, now(), transactionId, reason = response.message)
        }
    }

    private fun ExternalSysResponse.isSuccessful(): Boolean = this.result

    private fun ExternalSysResponse.isRetryable(): Boolean = this.message == "Temporary error"

    private fun isPaymentExpiredAt(deadline: Long): Boolean = now() - requestAverageProcessingTime.toMillis() > deadline

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}