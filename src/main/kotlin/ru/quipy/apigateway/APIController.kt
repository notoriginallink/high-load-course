package ru.quipy.apigateway

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.quipy.common.utils.LeakingBucketRateLimiter
import ru.quipy.orders.repository.OrderRepository
import ru.quipy.payments.logic.OrderPayer
import ru.quipy.payments.logic.PaymentAccountProperties
import ru.quipy.payments.metrics.PaymentMetrics
import java.time.Duration
import java.util.*

@RestController
class APIController(
    private val orderRepository: OrderRepository,
    private val orderPayer: OrderPayer,
    private val metrics: PaymentMetrics,
    paymentAccountProperties: List<PaymentAccountProperties>,
) {

    val maxRateLimitPerSec: Int = paymentAccountProperties.maxOf(PaymentAccountProperties::rateLimitPerSec)

    private val paymentRateLimiter = LeakingBucketRateLimiter(
        rate = maxRateLimitPerSec,
        window = Duration.ofSeconds(1),

        // (processingTime - averageProcessingTime) * rateLimitPerSec
        bucketSize = ((50.0 - 10.0) * maxRateLimitPerSec).toInt(),
    )

    @PostMapping("/users")
    fun createUser(@RequestBody req: CreateUserRequest): User {

        metrics.incIncomingTotal(url = "/users")
        return User(UUID.randomUUID(), req.name)
    }

    data class CreateUserRequest(val name: String, val password: String)

    data class User(val id: UUID, val name: String)

    @PostMapping("/orders")
    fun createOrder(@RequestParam userId: UUID, @RequestParam price: Int): Order {
        metrics.incIncomingTotal(url = "/orders")
        val order = Order(
            UUID.randomUUID(),
            userId,
            System.currentTimeMillis(),
            OrderStatus.COLLECTING,
            price,
        )
        return orderRepository.save(order)
    }

    data class Order(
        val id: UUID,
        val userId: UUID,
        val timeCreated: Long,
        val status: OrderStatus,
        val price: Int,
    )

    enum class OrderStatus {
        COLLECTING,
        PAYMENT_IN_PROGRESS,
        PAID,
    }

    @PostMapping("/orders/{orderId}/payment")
    suspend fun payOrder(@PathVariable orderId: UUID, @RequestParam deadline: Long): ResponseEntity<Any> {
        val paymentId = UUID.randomUUID()
        val order = orderRepository.findById(orderId)?.let {
            orderRepository.save(it.copy(status = OrderStatus.PAYMENT_IN_PROGRESS))
            it
        } ?: run {
            metrics.incIncomingTotal("/orders/{orderId}/payment", HttpStatus.NOT_FOUND)
            throw IllegalArgumentException("No such order $orderId")
        }

        val createdAt = orderPayer.processPayment(orderId, order.price, paymentId, deadline)
        metrics.incIncomingTotal("/orders/{orderId}/payment")
        return ResponseEntity.ok(PaymentSubmissionDto(createdAt, paymentId))
    }

    class PaymentSubmissionDto(
        val timestamp: Long,
        val transactionId: UUID
    )
}