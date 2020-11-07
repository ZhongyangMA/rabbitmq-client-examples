# rabbitmq-client-examples

For the purpose of learning, I created this repo of RabbitMQ code examples, which is much easier to follow than the tutorial given by the official site.  

Along with this repo, I have also published an article, where I recorded down some important aspects of message queue and several relating stuffs. Welcome to visit and leave your valuable comments: 

Article: *[Basic Concepts and Practices About RabbitMQ](https://zhongyangma.github.io/archivers/Basic-Concepts-About-RabbitMQ)*

# The AMQP Protocol

The AMQP (Advanced Message Queuing Protocol) is one of the protocols supported by RabbitMQ. The AMQP is a messaging protocol that enables conforming client applications to communicate with conforming messaging middleware brokers.

The AMQP Model has the following view of the world: messages are published to **exchanges**, then exchanges distribute message copies to **queues** based on rules called **bindings**. Then AMQP brokers either deliver messages to consumers subscribed to queues, or consumers fetch/pull messages from queues on demand.

# Exchanges

*Exchanges* are AMQP entities where messages are sent. Exchanges take a message and route it into zero or more queues. The routing algorithm used depends on the *exchange type* and rules called *bindings*. AMQP 0-9-1 brokers provide four exchange types: Direct, Fanout, Topic, Headers.

Exchanges can be durable or transient. Durable exchanges survive broker restart whereas transient exchanges do not (they have to be redeclared when broker comes back online). Not all scenarios and use cases require exchanges to be durable.

## Default Exchange

The default exchange is a **direct exchange** with no name (empty string) pre-declared by the broker.

> channel.exchangeDeclare(""[exchange name], BuiltinExchangeType.DIRECT);

It has one special property that makes it very useful for simple applications: every queue that is created is automatically bound to it with a **routing key** which is the same as the **queue name**.

> channel.basicPublish(""[exchange name], QUEUE_NAME[routing key], null, message.getBytes("UTF-8"));

The default exchange makes it seem like it is possible to deliver messages directly to queues, even though that is not technically what is happening.

## Direct Exchange

A direct exchange delivers messages to queues based on the message routing key. A direct exchange is ideal for the unicast routing of messages (although they can be used for multicast routing as well). Here is how it works: 

- A queue binds to the exchange with a routing key K

  > channel.queueBind(queue_name, exchange_name, routing_key);

- When a new message with routing key R arrives at the direct exchange, the exchange routes it to the queue if K = R

  > channel.basicPublish(exchange_name, routing_key, null, null);

Direct exchanges are often used to distribute tasks between multiple workers (instances of the same application) in a round robin manner. When doing so, it is important to understand that, in AMQP 0-9-1, messages are **load balanced between consumers but not between queues**.

## Fanout Exchange

A fanout exchange routes messages to all of the queues that are bound to it and the routing key is ignored. 

> channel.queueBind(queue_name, exchange_name, ""[routing key is ignored]);

If N queues are bound to a fanout exchange, when a new message is published to that exchange a copy of the message is delivered to all N queues. Fanout exchanges are ideal for the broadcast routing of messages.

## Topic Exchange

Topic exchanges route messages to one or many queues based on matching between a message routing key and the pattern that was used to bind a queue to an exchange.

> channel.queueBind(queue_name, exchange_name, "\*.orange.\*"[binding key]);

Topic exchanges have a very broad set of use cases. Whenever a problem involves multiple consumers/applications that selectively choose which type of messages they want to receive, the use of topic exchanges should be considered.

## Headers Exchange

A headers exchange is designed for routing on multiple attributes that are more easily expressed as message headers than a routing key. When the "x-match" argument is set to "any", just one matching header value is sufficient. Alternatively, setting "x-match" to "all" mandates that all the values must match.

```java
// sender
Map<String, Object> heardersMap = new HashMap<>();
heardersMap.put("api", "login");
heardersMap.put("version", 1.0);
heardersMap.put("random", UUID.randomUUID().toString());
AMQP.BasicProperties.Builder properties = new AMQP.BasicProperties().builder().headers(heardersMap);
String message = "Hello RabbitMQ!";
String EXCHANGE_NAME = "exchange.hearders";
channel.basicPublish(EXCHANGE_NAME, "", properties.build(), message.getBytes("UTF-8"));

// receiver
String EXCHANGE_NAME = "exchange.hearders";
channel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.HEADERS);
String queueName = channel.queueDeclare().getQueue();
Map<String, Object> arguments = new HashMap<>();
arguments.put("x-match", "any");
arguments.put("api", "login");
arguments.put("version", 1.0);
arguments.put("dataType", "json");
channel.queueBind(queueName, EXCHANGE_NAME, "", arguments);
```

Headers Exchange can be used as direct exchanges where the routing key does not have to be a string, it could be an integer or a hash (dictionary) for example.

# Queues

Queues store messages that are consumed by applications. Before a queue can be used it has to be declared. Declaring a queue will cause it to be created if it does not already exist. The declaration will have no effect if the queue does already exist and its attributes are the same as those in the declaration. When the existing queue attributes are not the same as those in the declaration a channel-level exception with code 406 (PRECONDITION_FAILED) will be raised.

## Queue Names

Applications may pick queue names or ask the broker to generate a name for them. Queue names may be up to 255 bytes of UTF-8 characters. An AMQP 0-9-1 broker can generate a unique queue name on behalf of an app.

> String queueName = channel.queueDeclare().getQueue();

Queue names starting with "amq." are reserved for internal use by the broker. Attempts to declare a queue with a name that violates this rule will result in a channel-level exception with reply code 403 (ACCESS_REFUSED).

## Queue Durability

Durable queues are persisted to disk and thus survive broker restarts. Queues that are not durable are called transient. Not all scenarios and use cases mandate queues to be durable.

Durability of a queue does not make *messages* that are routed to that queue durable. If broker is taken down and then brought back up, durable queue will be re-declared during broker startup, however, only *persistent* messages will be recovered.

```java
/**
  * Two things are required to make sure that messages aren't lost:
  * we need to mark both the queue and messages as durable.
  */
boolean durable = true;  // 1.make the queue durable.
channel.queueDeclare(TASK_QUEUE_NAME, durable, false, false, null);
String message = getMessage(argv);
channel.basicPublish("", TASK_QUEUE_NAME,
    MessageProperties.PERSISTENT_TEXT_PLAIN,  // 2.mark messages as persistent
    message.getBytes("UTF-8")
);
```

## Round Robin Dispatching

In AMQP 0-9-1, messages are load balanced between **consumers** but not between **queues**. For example:

- If a direct exchange is bound by two queues with the same routing key, this direct exchange will behave like a fanout.
- If two consumers subscribe the same queue, the queue will distribute tasks between multiple workers in a round robin manner.

# Message Acknowledgements

## Acknowledgement

Consumers may occasionally fail to process individual messages or will sometimes just crash. There is also the possibility of network issues causing problems. This raises a question: when should the AMQP broker remove messages from queues? The AMQP 0-9-1 specification proposes two choices:

1. **Automatic Acknowledgement**: AMQP broker remove messages from queues after broker sends a message to an application.
2. **Explicit (or manual) Acknowledgement**: AMQP broker remove messages from queues after the application sends back an acknowledgement.

To turn on the manual acknowledgement:

```java
boolean autoAck = false;  // manual ack (autoAck == false by default)
channel.basicConsume(QUEUE_NAME, autoAck, consumer);
//...
try {
    doWork(message);
} finally {
    // If a consumer dies without sending an ack, RabbitMQ will re-deliver the message
    channel.basicAck(envelope.getDeliveryTag(), false);
}
```

If a consumer dies without sending an acknowledgement the AMQP broker will redeliver it to another consumer or, if none are available at the time, the broker will wait until at least one consumer is registered for the same queue before attempting redelivery.

## Fair Dispatch

Imagine that in a situation with two workers, when all odd messages are heavy and even messages are light, one worker will be constantly busy and the other one will do hardly any work. Well, RabbitMQ just dispatches a message when the message enters the queue. It doesn't look at the number of unacknowledged messages for a consumer. It just blindly dispatches every n-th message to the n-th consumer.

In order to defeat that we can use the basicQos method with the prefetchCount = 1 setting. 

```java
int prefetchCount = 1;
channel.basicQos(prefetchCount);
```

This tells RabbitMQ not to give more than one message to a worker at a time. Or, in other words, don't dispatch a new message to a worker until it has processed and acknowledged the previous one. Instead, it will dispatch it to the next worker that is not still busy.

# Code Example

I created a repository to store the example code presented above, please visit:

**[https://github.com/ZhongyangMA/rabbitmq-client-examples](https://github.com/ZhongyangMA/rabbitmq-client-examples)**



# References

[1] AMQP 0-9-1 Model Explained: [https://www.rabbitmq.com/tutorials/amqp-concepts.html](https://www.rabbitmq.com/tutorials/amqp-concepts.html)

[2] AMQP协议简介: [https://blog.csdn.net/mx472756841/article/details/50815895](https://blog.csdn.net/mx472756841/article/details/50815895)

[3] RabbitMQ与AMQP协议详解: [https://www.cnblogs.com/frankyou/p/5283539.html](https://www.cnblogs.com/frankyou/p/5283539.html)


