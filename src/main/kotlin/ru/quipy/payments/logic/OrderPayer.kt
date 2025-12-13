package ru.quipy.payments.logic

import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class OrderPayer(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentService: PaymentService,
) {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    // Используем SynchronousQueue для полностью неблокирующей работы
    // При превышении лимита потоков будет бросаться RejectedExecutionException
    // Это обрабатывается в APIController
    private val paymentExecutor = ThreadPoolExecutor(
        100,                                                    // corePoolSize
        500,                                                    // maximumPoolSize
        70L,                                                    // keepAliveTime
        TimeUnit.SECONDS,                                       // unit
        SynchronousQueue(),                                     // workQueue - неблокирующая очередь
        NamedThreadFactory("payment-submission-executor"),      // threadFactory
        ThreadPoolExecutor.AbortPolicy()                        // handler - неблокирующая политика
    )

    private val coroutineScope = CoroutineScope(paymentExecutor.asCoroutineDispatcher())

    private val blockingExecutor = Executors.newFixedThreadPool(128)
    private val blockingDispatcher = blockingExecutor.asCoroutineDispatcher()

    val currentQueueSize: Int
        get() = paymentExecutor.queue.size

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        coroutineScope.launch {
            val createdEvent = withContext(blockingDispatcher) {
                paymentESService.create {
                    it.create(
                        id = paymentId,
                        orderId = orderId,
                        amount = amount,
                    )
                }
            }
            logger.trace("Payment ${createdEvent.paymentId} for order $orderId created.")

            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
        }
        return createdAt
    }
}