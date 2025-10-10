package ru.quipy.apigateway

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*
import ru.quipy.orders.repository.OrderRepository
import ru.quipy.payments.logic.OrderPayer
import ru.quipy.payments.metrics.PaymentMetrics
import java.util.*

@RestController
class APIController {

    val logger: Logger = LoggerFactory.getLogger(APIController::class.java)

    @Autowired
    private lateinit var orderRepository: OrderRepository

    @Autowired
    private lateinit var orderPayer: OrderPayer

    @Autowired
    private lateinit var metrics: PaymentMetrics

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
    fun payOrder(@PathVariable orderId: UUID, @RequestParam deadline: Long): PaymentSubmissionDto {
        metrics.incIncomingTotal(url = "/orders/{orderId}/payment")
        val paymentId = UUID.randomUUID()
        val order = orderRepository.findById(orderId)?.let {
            orderRepository.save(it.copy(status = OrderStatus.PAYMENT_IN_PROGRESS))
            it
        } ?: throw IllegalArgumentException("No such order $orderId")


        val createdAt = orderPayer.processPayment(orderId, order.price, paymentId, deadline)
        return PaymentSubmissionDto(createdAt, paymentId)
    }

    class PaymentSubmissionDto(
        val timestamp: Long,
        val transactionId: UUID
    )
}