package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import ru.quipy.common.utils.NonBlockingOngoingWindow
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.metrics.PaymentMetrics
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
//    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val metrics: PaymentMetrics,
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()
        
        fun now() = System.currentTimeMillis()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
//    private val rateLimiter = makeRateLimiter(accountName, rateLimitPerSec)
    private val rateLimiter = SlidingWindowRateLimiter(
        rate = rateLimitPerSec,
        window = Duration.ofSeconds(1),
    )
    private val ongoingWindow = NonBlockingOngoingWindow(maxWinSize = parallelRequests)
    private val httpExecutor = Executors.newFixedThreadPool(150)

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .version(HttpClient.Version.HTTP_2)
        .executor(httpExecutor)
        .build()

    override suspend fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.info("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()
        val currentTime = now()
//        withContext(Dispatchers.IO) {
//            paymentESService.update(paymentId) {
//                it.logSubmission(success = true, transactionId, currentTime, Duration.ofMillis(currentTime - paymentStartedAt))
//            }
//        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")
        if (isPaymentExpiredAt(deadline)) {
//            withContext(Dispatchers.IO) {
//                paymentESService.update(paymentId) {
//                    it.logProcessing(success = false, currentTime, transactionId = transactionId, reason = "Deadline")
//                }
//            }
            metrics.incTimeoutPayment(account = accountName)
            return
        }

        performExternalRequest(
            paymentId = paymentId,
            amount = amount,
            transactionId = transactionId,
            deadline = deadline,
        )
    }

    private suspend fun performExternalRequest(
        paymentId: UUID,
        amount: Int,
        transactionId: UUID,
        deadline: Long,
    ) {

        while (ongoingWindow.putIntoWindow() is NonBlockingOngoingWindow.WindowResponse.Fail) {
            delay(10)
        }

        try {
            rateLimiter.tickBlocking()

            if (isPaymentExpiredAt(deadline)) {
//                withContext(Dispatchers.IO) {
//                    paymentESService.update(paymentId) {
//                        it.logProcessing(success = false, now(), transactionId = transactionId, reason = "Deadline")
//                    }
//                }
                metrics.incTimeoutPayment(account = accountName)
                return
            }

            val uri = URI.create("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
            val request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(1))
                .method(HttpMethod.POST.name(), HttpRequest.BodyPublishers.noBody())
                .build()

            val startTime = now()
            val httpResponse = try {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: Exception) {
                logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)
                metrics.incFailedPayment(account = accountName)
//                withContext(Dispatchers.IO) {
//                    paymentESService.update(paymentId) {
//                        it.logProcessing(false, now(), transactionId, reason = e.message)
//                    }
//                }
                return
            }

            val responseBody = httpResponse.body()
            metrics.recordOutgoingRequest(now() - startTime)
            metrics.incOutgoing(
                account = accountName,
                responseCode = httpResponse.statusCode().toString(),
                responseDesc = HttpStatus.valueOf(httpResponse.statusCode()).reasonPhrase,
            )

            val response = try {
                mapper.readValue(responseBody, ExternalSysResponse::class.java)
            } catch (ex: Exception) {
                logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${httpResponse.statusCode()}, reason: $responseBody", ex)
                metrics.incFailedPayment(account = accountName)
                ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, ex.message)
            }

            logger.info("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${response.result}, message: ${response.message}")

            when {
                response.isSuccessful() -> metrics.incSuccessPayment(account = accountName)
                else -> metrics.incFailedPayment(account = accountName)
            }

//            withContext(Dispatchers.IO) {
//                paymentESService.update(paymentId) {
//                    it.logProcessing(response.result, now(), transactionId, reason = response.message)
//                }
//            }
        } finally {
            ongoingWindow.releaseWindow()
        }
    }

    private fun ExternalSysResponse.isSuccessful(): Boolean = this.result

    private fun isPaymentExpiredAt(deadline: Long): Boolean = now() - requestAverageProcessingTime.toMillis() > deadline

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}