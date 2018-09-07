package com.solarwind.workQueues;

import com.rabbitmq.client.*;
import java.io.IOException;

/**
 * Round-robin dispatching
 */
public class Worker {

    private static final String TASK_QUEUE_NAME = "task_queue";

    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        final Connection connection = factory.newConnection();
        final Channel channel = connection.createChannel();

        boolean durable = true;
        channel.queueDeclare(TASK_QUEUE_NAME, durable, false, false, null);
        System.out.println(" [*] Waiting for messages. To exit press CTRL+C");

        /**
         * Don't dispatch a new message to a worker until it has processed and acknowledged
         * the previous one. Instead, it will dispatch it to the next worker that is not still busy.
         */
        int prefetchCount = 1;
        channel.basicQos(prefetchCount);

        final Consumer consumer = new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                String message = new String(body, "UTF-8");

                System.out.println(" [x] Received '" + message + "'");
                try {
                    doWork(message);
                } finally {
                    System.out.println(" [x] Done");
                    /**
                     * If a consumer dies without sending an ack, RabbitMQ will re-queue it.
                     */
                    channel.basicAck(envelope.getDeliveryTag(), false);
                }
            }
        };
        boolean autoAck = false;  // autoAck == false by default, manual ack
        channel.basicConsume(TASK_QUEUE_NAME, autoAck, consumer);
        // Soon after the worker dies all unacknowledged messages will be redelivered
    }

    private static void doWork(String task) {
        for (char ch : task.toCharArray()) {
            if (ch == '.') {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException _ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
