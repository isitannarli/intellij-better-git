package dev.bettergit.core.hosting.avatar

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow

class LimitedAvatarBodySubscriberTest {
    @Test fun `accepts exactly the limit across multiple chunks and buffer slices`() {
        val subscriber = LimitedAvatarBodySubscriber(4)
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(0, 1, 2, 0), 1, 2)))
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(3)), ByteBuffer.wrap(byteArrayOf(4))))
        subscriber.onComplete()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), subscriber.body.toCompletableFuture().get())
        assertFalse(subscription.cancelled)
    }

    @Test fun `cancels oversized chunked content before buffering the excess`() {
        val subscriber = LimitedAvatarBodySubscriber(4)
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(4)), ByteBuffer.wrap(byteArrayOf(5))))
        assertTrue(subscription.cancelled)
        subscriber.onComplete()
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(6))))
        val error = assertThrows(ExecutionException::class.java) { subscriber.body.toCompletableFuture().get() }
        assertInstanceOf(IOException::class.java, error.cause)
    }

    @Test fun `rejects an oversized first batch without waiting for completion`() {
        val subscriber = LimitedAvatarBodySubscriber(1)
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2))))
        assertTrue(subscription.cancelled)
        assertTrue(subscriber.body.toCompletableFuture().isCompletedExceptionally)
    }

    @Test fun `propagates a transport failure instead of returning a partial image`() {
        val subscriber = LimitedAvatarBodySubscriber(4)
        subscriber.onSubscribe(Subscription())
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1))))
        val failure = IOException("Connection closed")
        subscriber.onError(failure)
        subscriber.onComplete()
        val error = assertThrows(ExecutionException::class.java) { subscriber.body.toCompletableFuture().get() }
        assertSame(failure, error.cause)
    }

    private class Subscription : Flow.Subscription {
        var cancelled = false
        override fun request(n: Long) = Unit
        override fun cancel() { cancelled = true }
    }
}
