/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.utils.async;

import static java.util.Arrays.asList;
import static software.amazon.awssdk.utils.async.SimplePublisher.QueueEntry.Type.CANCEL;
import static software.amazon.awssdk.utils.async.SimplePublisher.QueueEntry.Type.ON_COMPLETE;
import static software.amazon.awssdk.utils.async.SimplePublisher.QueueEntry.Type.ON_ERROR;
import static software.amazon.awssdk.utils.async.SimplePublisher.QueueEntry.Type.ON_NEXT;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.annotations.SdkProtectedApi;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.Validate;

/**
 * A {@link Publisher} to which callers can {@link #send(Object)} messages, simplifying the process of implementing a publisher.
 *
 * <p><b>Operations</b>
 *
 * <p>The {@code SimplePublisher} supports three simplified operations:
 * <ol>
 *     <li>{@link #send(Object)} for sending messages</li>
 *     <li>{@link #complete()} for indicating the successful end of messages</li>
 *     <li>{@link #error(Throwable)} for indicating the unsuccessful end of messages</li>
 * </ol>
 *
 * Each of these operations returns a {@link CompletableFuture} for indicating when the message has been successfully sent.
 *
 * <p>Callers are expected to invoke a series of {@link #send(Object)}s followed by a single {@link #complete()} or
 * {@link #error(Throwable)}. See the documentation on each operation for more details.
 *
 * <p>This publisher will store an unbounded number of messages. It is recommended that callers limit the number of in-flight
 * {@link #send(Object)} operations in order to bound the amount of memory used by this publisher.
 */
@SdkProtectedApi
public final class SimplePublisher<T> implements Publisher<T> {
    private static final Logger log = Logger.loggerFor(SimplePublisher.class);

    /**
     * Track the amount of outstanding demand requested by the active subscriber.
     */
    private final AtomicLong outstandingDemand = new AtomicLong();

    /**
     * The queue of events to be processed, in the order they should be processed.
     *
     * <p>All logic within this publisher is represented using events in this queue. This ensures proper ordering of events
     * processing and simplified reasoning about thread safety.
     */
    private final Queue<QueueEntry<T>> eventQueue = new ConcurrentLinkedQueue<>();

    /**
     * When processing the {@link #eventQueue}, these are the entries that should be skipped (and failed). This is used to
     * safely drain the queue when there are urgent events needing processing, like a {@link Subscription#cancel()}.
     */
    private final Set<QueueEntry.Type> entryTypesToFail = new CopyOnWriteArraySet<>();

    /**
     * Whether the {@link #eventQueue} is currently being processed. Only one thread may read events from the queue at a time.
     */
    private final AtomicBoolean processingQueue = new AtomicBoolean(false);

    /**
     * An exception that should be raised to any failed {@link #send(Object)}, {@link #complete()} or {@link #error(Throwable)}
     * operations. This is used to stop accepting messages after the downstream subscription is cancelled or after the
     * caller sends a {@code complete()} or {@code #error()}.
     *
     * <p>This is a supplier to avoid the cost of creating an exception in the successful code path.
     */
    private final AtomicReference<Supplier<RuntimeException>> rejectException = new AtomicReference<>();

    /**
     * The subscriber provided via {@link #subscribe(Subscriber)}. This publisher only supports a single subscriber.
     */
    private Subscriber<? super T> subscriber;

    /**
     * Send a message using this publisher.
     *
     * <p>Messages sent using this publisher will eventually be sent to a downstream subscriber, in the order they were
     * written. When the message is sent to the subscriber, the returned future will be completed successfully.
     *
     * <p>This method may be invoked concurrently when the order of messages is not important.
     *
     * <p>In the time between when this method is invoked and the returned future is not completed, this publisher stores the
     * request message in memory. Callers are recommended to limit the number of sends in progress at a time to bound the
     * amount of memory used by this publisher.
     *
     * <p>The returned future will be completed exceptionally if the downstream subscriber cancels the subscription, or
     * if the {@code send} call was performed after a {@link #complete()} or {@link #error(Throwable)} call.
     *
     * @param value The message to send. Must not be null.
     * @return A future that is completed when the message is sent to the subscriber.
     */
    public CompletableFuture<Void> send(T value) {
        log.trace(() -> "Received send() with " + value);

        OnNextQueueEntry<T> entry = new OnNextQueueEntry<>(value);
        try {
            Validate.notNull(value, "Null cannot be written.");
            validateRejectState();
            eventQueue.add(entry);
            processEventQueue();
        } catch (RuntimeException t) {
            entry.resultFuture.completeExceptionally(t);
        }
        return entry.resultFuture;
    }

    /**
     * Indicate that no more {@link #send(Object)} calls will be made, and that stream of messages is completed successfully.
     *
     * <p>This can be called before any in-flight {@code send} calls are complete. Such messages will be processed before the
     * stream is treated as complete. The returned future will be completed successfully when the {@code complete} is sent to
     * the downstream subscriber.
     *
     * <p>After this method is invoked, any future {@link #send(Object)}, {@code complete()} or {@link #error(Throwable)}
     * calls will be completed exceptionally and not be processed.
     *
     * <p>The returned future will be completed exceptionally if the downstream subscriber cancels the subscription, or
     * if the {@code complete} call was performed after a {@code complete} or {@link #error(Throwable)} call.
     *
     * @return A future that is completed when the complete has been sent to the downstream subscriber.
     */
    public CompletableFuture<Void> complete() {
        log.trace(() -> "Received complete()");

        OnCompleteQueueEntry<T> entry = new OnCompleteQueueEntry<>();

        try {
            validateRejectState();
            setRejectExceptionOrThrow(() -> new IllegalStateException("complete() has been invoked"));
            eventQueue.add(entry);
            processEventQueue();
        } catch (RuntimeException t) {
            entry.resultFuture.completeExceptionally(t);
        }
        return entry.resultFuture;
    }

    /**
     * Indicate that no more {@link #send(Object)} calls will be made, and that streaming of messages has failed.
     *
     * <p>This can be called before any in-flight {@code send} calls are complete. Such messages will be processed before the
     * stream is treated as being in-error. The returned future will be completed successfully when the {@code error} is
     * sent to the downstream subscriber.
     *
     * <p>After this method is invoked, any future {@link #send(Object)}, {@link #complete()} or {@code #error(Throwable)}
     * calls will be completed exceptionally and not be processed.
     *
     * <p>The returned future will be completed exceptionally if the downstream subscriber cancels the subscription, or
     * if the {@code complete} call was performed after a {@link #complete()} or {@code error} call.
     *
     * @param error The error to send.
     * @return A future that is completed when the exception has been sent to the downstream subscriber.
     */
    public CompletableFuture<Void> error(Throwable error) {
        log.trace(() -> "Received error() with " + error, error);

        OnErrorQueueEntry<T> entry = new OnErrorQueueEntry<>(error);

        try {
            validateRejectState();
            setRejectExceptionOrThrow(() -> new IllegalStateException("error() has been invoked"));
            eventQueue.add(entry);
            processEventQueue();
        } catch (RuntimeException t) {
            entry.resultFuture.completeExceptionally(t);
        }
        return entry.resultFuture;
    }

    /**
     * A method called by the downstream subscriber in order to subscribe to the publisher.
     */
    @Override
    public void subscribe(Subscriber<? super T> s) {
        if (subscriber != null) {
            s.onSubscribe(new NoOpSubscription());
            s.onError(new IllegalStateException("Only one subscription may be active at a time."));
        }
        this.subscriber = s;
        s.onSubscribe(new SubscriptionImpl());
        processEventQueue();
    }

    /**
     * Process the messages in the event queue. This is invoked after every operation on the publisher that changes the state
     * of the event queue.
     *
     * <p>Internally, this method will only be executed by one thread at a time. Any calls to this method will another thread
     * is processing the queue will return immediately. This ensures: (1) thread safety in queue processing, (2) mutual recursion
     * between onSubscribe/onNext with {@link Subscription#request(long)} are impossible.
     */
    private void processEventQueue() {
        do {
            if (!processingQueue.compareAndSet(false, true)) {
                // Some other thread is processing the queue, so we don't need to.
                return;
            }

            try {
                doProcessQueue();
            } catch (Throwable e) {
                panicAndDie(e);
                break;
            } finally {
                processingQueue.set(false);
            }

            // Once releasing the processing-queue flag, we need to double-check that the queue still doesn't need to be
            // processed, because new messages might have come in since we decided to release the flag.
        } while (shouldProcessQueueEntry(eventQueue.peek()));
    }

    /**
     * Pop events off of the queue and process them in the order they are given, returning when we can no longer process the
     * event at the head of the queue.
     *
     * <p>Invoked only from within the {@link #processEventQueue()} method with the {@link #processingQueue} flag held.
     */
    private void doProcessQueue() {
        while (true) {
            QueueEntry<T> entry = eventQueue.peek();

            if (!shouldProcessQueueEntry(entry)) {
                // We're done processing entries.
                return;
            }

            if (entryTypesToFail.contains(entry.type())) {
                // We're supposed to skip this entry type. Fail it and move on.
                entry.resultFuture.completeExceptionally(rejectException.get().get());
            } else {
                switch (entry.type()) {
                    case ON_NEXT:
                        OnNextQueueEntry<T> onNextEntry = (OnNextQueueEntry<T>) entry;

                        log.trace(() -> "Calling onNext() with " + onNextEntry.value);
                        subscriber.onNext(onNextEntry.value);
                        long newDemand = outstandingDemand.decrementAndGet();
                        log.trace(() -> "Decreased demand to " + newDemand);
                        break;
                    case ON_COMPLETE:
                        entryTypesToFail.addAll(asList(ON_NEXT, ON_COMPLETE, ON_ERROR));
                        log.trace(() -> "Calling onComplete()");
                        subscriber.onComplete();
                        break;
                    case ON_ERROR:
                        OnErrorQueueEntry<T> onErrorEntry = (OnErrorQueueEntry<T>) entry;

                        entryTypesToFail.addAll(asList(ON_NEXT, ON_COMPLETE, ON_ERROR));
                        log.trace(() -> "Calling onError() with " + onErrorEntry.failure, onErrorEntry.failure);
                        subscriber.onError(onErrorEntry.failure);
                        break;
                    case CANCEL:
                        subscriber = null; // Allow subscriber to be garbage collected after cancellation.
                        break;
                    default:
                        // Should never happen. Famous last words?
                        throw new IllegalStateException("Unknown entry type: " + entry.type());
                }

                entry.resultFuture.complete(null);
            }

            eventQueue.remove();
        }
    }

    /**
     * Return true if we should process the provided queue entry.
     */
    private boolean shouldProcessQueueEntry(QueueEntry<T> entry) {
        if (subscriber == null) {
            // We don't have a subscriber yet.
            return false;
        }

        if (entry == null) {
            // The queue is empty.
            return false;
        }

        if (entry.type() != ON_NEXT) {
            // This event isn't an on-next event, so we don't need subscriber demand in order to process it.
            return true;
        }

        if (entryTypesToFail.contains(ON_NEXT)) {
            // This is an on-next call (decided above), but we're failing on-next calls. Go ahead and fail it.
            return true;
        }

        // This is an on-next event and we're not failing on-next events, so make sure we have demand available before
        // processing it.
        return outstandingDemand.get() > 0;
    }

    /**
     * Invoked from within {@link #processEventQueue()} when we can't process the queue for some reason. This is likely
     * caused by a downstream subscriber throwing an exception from {@code onNext}, which it should never do.
     *
     * <p>Here we try our best to fail all of the entries in the queue, so that no callers have "stuck" futures.
     */
    private void panicAndDie(Throwable cause) {
        try {
            // Create exception here instead of in supplier to preserve a more-useful stack trace.
            RuntimeException failure = new IllegalStateException("Encountered fatal error in publisher", cause);
            rejectException.compareAndSet(null, () -> failure);
            entryTypesToFail.addAll(asList(QueueEntry.Type.values()));
            subscriber.onError(cause instanceof Error ? cause : failure);

            while (true) {
                QueueEntry<T> entry = eventQueue.poll();
                if (entry == null) {
                    break;
                }
                entry.resultFuture.completeExceptionally(failure);
            }
        } catch (Throwable t) {
            t.addSuppressed(cause);
            log.error(() -> "Failed while processing a failure. This could result in stuck futures.", t);
        }
    }

    /**
     * Ensure that {@link #rejectException} is null. If it is not, throw the exception.
     */
    private void validateRejectState() {
        if (rejectException.get() != null) {
            throw rejectException.get().get();
        }
    }

    /**
     * Set the {@link #rejectException}, if it is null. If it is not, throw the exception.
     */
    private void setRejectExceptionOrThrow(Supplier<RuntimeException> rejectedException) {
        if (!rejectException.compareAndSet(null, rejectedException)) {
            throw rejectException.get().get();
        }
    }

    /**
     * The subscription passed to the first {@link #subscriber} that subscribes to this publisher. This allows the downstream
     * subscriber to request for more {@code onNext} calls or to {@code cancel} the stream of messages.
     */
    private class SubscriptionImpl implements Subscription {
        @Override
        public void request(long n) {
            log.trace(() -> "Received request() with " + n);
            if (n <= 0) {
                // Create exception here instead of in supplier to preserve a more-useful stack trace.
                IllegalArgumentException failure = new IllegalArgumentException("A downstream publisher requested an invalid "
                                                                                + "amount of data: " + n);
                rejectException.compareAndSet(null, () -> failure);
                eventQueue.add(new OnErrorQueueEntry<>(failure));
                entryTypesToFail.addAll(asList(ON_NEXT, ON_COMPLETE));
                processEventQueue();
            } else {
                long newDemand = outstandingDemand.updateAndGet(current -> {
                    if (Long.MAX_VALUE - current < n) {
                        return Long.MAX_VALUE;
                    }

                    return current + n;
                });
                log.trace(() -> "Increased demand to " + newDemand);
                processEventQueue();
            }
        }

        @Override
        public void cancel() {
            log.trace(() -> "Received cancel()");

            // Create exception here instead of in supplier to preserve a more-useful stack trace.
            IllegalStateException failure = new IllegalStateException("A downstream publisher has cancelled the subscription.");
            rejectException.compareAndSet(null, () -> failure);
            eventQueue.add(new CancelQueueEntry<>());
            entryTypesToFail.addAll(asList(ON_NEXT, ON_COMPLETE, ON_ERROR));
            processEventQueue();
        }
    }

    /**
     * An entry in the {@link #eventQueue}.
     */
    abstract static class QueueEntry<T> {
        /**
         * The future that was returned to a {@link #send(Object)}, {@link #complete()} or {@link #error(Throwable)} message.
         */
        protected final CompletableFuture<Void> resultFuture = new CompletableFuture<>();

        /**
         * Retrieve the type of this queue entry.
         */
        protected abstract Type type();

        protected enum Type {
            ON_NEXT,
            ON_COMPLETE,
            ON_ERROR,
            CANCEL
        }
    }

    /**
     * An entry added when we get a {@link #send(Object)} call.
     */
    private static final class OnNextQueueEntry<T> extends QueueEntry<T> {
        private final T value;

        private OnNextQueueEntry(T value) {
            this.value = value;
        }

        @Override
        protected Type type() {
            return ON_NEXT;
        }
    }

    /**
     * An entry added when we get a {@link #complete()} call.
     */
    private static final class OnCompleteQueueEntry<T> extends QueueEntry<T> {
        @Override
        protected Type type() {
            return ON_COMPLETE;
        }
    }

    /**
     * An entry added when we get an {@link #error(Throwable)} call.
     */
    private static final class OnErrorQueueEntry<T> extends QueueEntry<T> {
        private final Throwable failure;

        private OnErrorQueueEntry(Throwable failure) {
            this.failure = failure;
        }

        @Override
        protected Type type() {
            return ON_ERROR;
        }
    }

    /**
     * An entry added when we get a {@link SubscriptionImpl#cancel()} call.
     */
    private static final class CancelQueueEntry<T> extends QueueEntry<T> {
        @Override
        protected Type type() {
            return CANCEL;
        }
    }

    /**
     * A subscription that does nothing. This is used for signaling {@code onError} to subscribers that subscribe to this
     * publisher for the second time. Only one subscriber is supported.
     */
    private static final class NoOpSubscription implements Subscription {
        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    }
}
