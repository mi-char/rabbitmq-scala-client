package com.avast.client.rabbitmq;

import com.avast.client.api.GenericAsyncHandler;
import com.avast.client.api.exceptions.RequestConnectException;
import com.avast.jmx.JMXProperty;
import com.rabbitmq.client.ExceptionHandler;
import com.rabbitmq.client.QueueingConsumer;
import com.rabbitmq.client.Recoverable;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Meter;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created <b>15.10.2014</b><br>
 * The receiver will wait for first listener, then it will start to receive messages.
 *
 * @author Jenda Kolena, kolena@avast.com
 */
@SuppressWarnings("unused")
public class DefaultRabbitMQReceiver extends RabbitMQClientBase implements RabbitMQReceiver {

    protected final Meter receivedMeter;
    protected final Meter failedMeter;

    protected QueueingConsumer consumer;

    protected final AtomicReference<GenericAsyncHandler<QueueingConsumer.Delivery>> listener = new AtomicReference<>(null);
    protected final Semaphore listenerMutex = new Semaphore(0);

    protected final Set<Long> failedTags = new LinkedHashSet<>(2);
    protected final boolean allowRetry;

    protected Thread listenerThread;

    protected final AtomicInteger failed = new AtomicInteger(0);

    public DefaultRabbitMQReceiver(final String host, final String username, final String password, final String queue, final boolean allowRetry, final int connectionTimeout, final int recoveryTimeout, final SSLContext sslContext, final ExceptionHandler exceptionHandler, final String jmxGroup) throws RequestConnectException {
        super("RECEIVER", host, username, password, queue, connectionTimeout, recoveryTimeout, sslContext, exceptionHandler, jmxGroup);

        this.allowRetry = allowRetry;

        try {
            startConsumer(queue);
        } catch (IOException e) {
            final URI uri = getUri();
            LOG.debug("Error while connecting to the " + uri, e);
            throw new RequestConnectException(e, uri);
        }

        receivedMeter = Metrics.newMeter(getMetricName("received"), "receivedMessages", TimeUnit.SECONDS);
        failedMeter = Metrics.newMeter(getMetricName("failed"), "failedMessages", TimeUnit.SECONDS);

    }

    @Override
    protected void onChannelRecovered(Recoverable recoverable) {
        try {
            startConsumer(queue);
        } catch (IOException e) {
            LOG.error("Error while restarting the consumer", e);
        }
    }

    protected void startConsumer(String queue) throws IOException {
        if (consumer != null) {
            channel.basicCancel(consumer.getConsumerTag());
        }

        consumer = new QueueingConsumer(channel);
        channel.basicConsume(queue, false, consumer);

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (listenerThread == null || !listenerThread.isAlive()) {
                    planListener(); //start or restart the listener
                }
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void setListener(final GenericAsyncHandler<QueueingConsumer.Delivery> listener) {
        this.listener.set(listener);
        listenerMutex.release(100000);//for sure
    }

    protected synchronized void planListener() {
        LOG.debug("Waiting for listener");
        try {
            listenerMutex.acquire();//just wait for some listener
            listenerMutex.release();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (listenerThread == null || !listenerThread.isAlive()) {
            listenerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!closed.get()) {
                        if (!channel.isOpen()) {
                            LOG.debug("Channel failure detected, skipping");
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                                LOG.debug("Error while receiver throttling", e);
                            }
                            continue;
                        }

                        final int failedCnt = DefaultRabbitMQReceiver.this.failed.get();
                        if (failedCnt > 0) {
                            final int d = 500 * (failedCnt % 100);
                            LOG.debug("Throttling the receiver, delaying " + d + " ms");
                            try {
                                Thread.sleep(d);
                            } catch (InterruptedException e) {
                                LOG.debug("Error while receiver throttling", e);
                            }
                        }

                        LOG.debug("Waiting for message");
                        try {
                            final QueueingConsumer.Delivery delivery = consumer.nextDelivery();
                            final long deliveryTag = delivery.getEnvelope().getDeliveryTag();

                            LOG.debug("Received message, length " + delivery.getBody().length + "B");

                            boolean error = false;

                            failed.set(0);//reset the error indicator

                            final GenericAsyncHandler<QueueingConsumer.Delivery> listener = DefaultRabbitMQReceiver.this.listener.get();

                            try {
                                if (listener != null)//that would be weird!
                                    listener.completed(delivery);
                            } catch (Exception e) {
                                LOG.info("Error while executing the listener", e);
                                error = true;

                                if (allowRetry) {
                                    if (failedTags.contains(deliveryTag)) { //when it has failedCnt before, ACK it, or add to retry list
                                        failedMeter.mark();
                                        LOG.warn("Processing of listener has failed");
                                        ack(deliveryTag);
                                    } else {
                                        LOG.debug("Processing of listener has failed, but retry is allowed");
                                        failedTags.add(deliveryTag);
                                    }
                                } else {
                                    LOG.warn("Processing of listener has failed");
                                    ack(deliveryTag);
                                }
                            }

                            if (!error) {
                                ack(deliveryTag);
                                receivedMeter.mark();
                            }
                        } catch (Exception e) {
                            DefaultRabbitMQReceiver.this.failed.incrementAndGet();
                            LOG.debug("Error while receiving new message", e);

                            final GenericAsyncHandler<QueueingConsumer.Delivery> listener = DefaultRabbitMQReceiver.this.listener.get();
                            if (listener != null)//that would be weird!
                                listener.failed(e);
                        }
                    }
                }
            }, "mqlistener-" + queue + "-" + (System.currentTimeMillis() / 1000));
            listenerThread.start();
        }
    }

    protected void ack(long tag) {
        LOG.debug("Sending ACK to message with tag " + tag);
        try {
            channel.basicAck(tag, false);
        } catch (IOException e) {
            LOG.warn("Cannot ACK message with tag " + tag, e);
        }
    }

    /**
     * Queries whether this client is alive; it means it wasn't closed, it is connected to the server and it has some listener so it's actively receiving messages.
     *
     * @return TRUE if this client is connected to the server. Returns FALSE e.g. in case of connection failure.
     */
    @JMXProperty(name = "alive")
    @Override
    public boolean isAlive() {
        return super.isAlive() && listenerThread != null && listenerThread.isAlive();
    }
}
