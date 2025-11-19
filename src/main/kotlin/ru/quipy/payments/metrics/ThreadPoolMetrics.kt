package ru.quipy.payments.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Service
import ru.quipy.payments.logic.OrderPayer

@Service
class ThreadPoolMetrics(
    private val registry: MeterRegistry,
    orderPayer: OrderPayer,
) {

    private val queueSize = MultiGauge
        .builder("thread_executors_queue_size")
        .description("Queue size of thread executor")
        .register(registry)
        .register(
            listOf("payment-submission-executor").map { name ->
                MultiGauge.Row.of(Tags.of("name", name)) { orderPayer.currentQueueSize }
            }
        )

    fun incRejectedCount(executorName: String) = Counter
        .builder("thread_executors_rejected_count")
        .tags("executor", executorName)
        .register(registry)
        .increment()
}