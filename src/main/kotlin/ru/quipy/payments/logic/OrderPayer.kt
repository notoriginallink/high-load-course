package ru.quipy.payments.logic

import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.metrics.ThreadPoolMetrics
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class OrderPayer(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentService: PaymentService,
    @param:Lazy private val threadPoolMetrics: ThreadPoolMetrics,
) {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    private val paymentExecutor = ThreadPoolExecutor(
        100,                                                    // corePoolSize
        1200,                                                    // maximumPoolSize
        70,                                                    // keepAliveTime
        TimeUnit.SECONDS,                                       // unit
        LinkedBlockingQueue(20_000),                            // workQueue - неблокирующая очередь
        NamedThreadFactory("payment-submission-executor"),      // threadFactory
        CallerBlockingRejectedExecutionHandler(threadPoolMetrics,  "payment-submission-executor")
    )

    val currentQueueSize: Int
        get() = paymentExecutor.queue.size

    val executorScope = CoroutineScope(paymentExecutor.asCoroutineDispatcher())

    suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        executorScope.launch {
            val createdEvent = paymentESService.create {
                it.create(
                    id = paymentId,
                    orderId = orderId,
                    amount = amount,
                )
            }
            logger.trace("Payment ${createdEvent.paymentId} for order $orderId created.")

            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
        }
        return createdAt
    }
}