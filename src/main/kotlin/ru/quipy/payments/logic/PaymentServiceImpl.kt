package ru.quipy.payments.logic

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.quipy.payments.metrics.PaymentMetrics
import java.util.*


@Service
class PaymentSystemImpl(
    private val paymentAccounts: List<PaymentExternalSystemAdapter>,
    private val metrics: PaymentMetrics,
) : PaymentService {
    companion object {
        val logger = LoggerFactory.getLogger(PaymentSystemImpl::class.java)
    }

    override suspend fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        coroutineScope {
            paymentAccounts.map { account ->
                async {
                    account.performPaymentAsync(paymentId, amount, paymentStartedAt, deadline)
                    metrics.incIncoming(account.name())
                }
            }.awaitAll()
        }
    }
}