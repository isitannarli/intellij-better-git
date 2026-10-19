package dev.bettergit.core.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow

class BoundedAiBodySubscriberTest {
    @Test fun `body limit cancels the stream before appending oversized content`() {
        val subscriber = BoundedAiBodySubscriber(4)
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1,2,3))))
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(4,5))))
        assertTrue(subscription.cancelled)
        val failure = assertThrows(ExecutionException::class.java) { subscriber.body.toCompletableFuture().get() }
        assertInstanceOf(AiGenerationException::class.java, failure.cause)
    }
    @Test fun `multibyte text is decoded after complete byte collection`() {
        val subscriber = BoundedAiBodySubscriber(10)
        subscriber.onSubscribe(Subscription())
        val bytes = "\u00E9".toByteArray()
        subscriber.onNext(listOf(ByteBuffer.wrap(bytes.copyOfRange(0,1))))
        subscriber.onNext(listOf(ByteBuffer.wrap(bytes.copyOfRange(1,2))))
        subscriber.onComplete()
        assertEquals("\u00E9", subscriber.body.toCompletableFuture().get())
    }
    private class Subscription : Flow.Subscription {
        var cancelled = false
        override fun request(n: Long) = Unit
        override fun cancel() { cancelled = true }
    }
}
