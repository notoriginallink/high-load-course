package ru.quipy.payments.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class OrderPayer(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentService: PaymentService,
) {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    private val paymentExecutor = ThreadPoolExecutor(
        100,                                                    // corePoolSize
        1200,                                                   // maximumPoolSize
        70L,                                                    // keepAliveTime
        TimeUnit.SECONDS,                                       // unit
        LinkedBlockingQueue(11_000),                            // workQueue
        NamedThreadFactory("payment-submission-executor"),      // threadFactory
        CallerBlockingRejectedExecutionHandler()                // handler
    )

    private val coroutineScope = CoroutineScope(paymentExecutor.asCoroutineDispatcher())

    val currentQueueSize: Int
        get() = paymentExecutor.queue.size

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        coroutineScope.launch {
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