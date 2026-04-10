package uk.jtoye.core.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

@Configuration
public class RabbitMQConfig {
    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    public static final String ORDER_EVENTS_EXCHANGE = "order.events";
    public static final String ORDER_EVENTS_QUEUE = "order.state-changes";
    public static final String ORDER_EVENTS_ROUTING_KEY = "order.state.changed";

    public static final String DLX_EXCHANGE = "order.events.dlx";
    public static final String DLQ_QUEUE = "order.state-changes.dlq";

    public static final String PAYMENT_EVENTS_EXCHANGE = "payment.events";
    public static final String PAYMENT_EVENTS_QUEUE = "payment.events";
    public static final String PAYMENT_EVENTS_DLX = "payment.events.dlx";
    public static final String PAYMENT_EVENTS_DLQ = "payment.events.dlq";

    @Bean
    public TopicExchange orderEventsExchange() {
        return new TopicExchange(ORDER_EVENTS_EXCHANGE);
    }

    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue orderEventsQueue() {
        return QueueBuilder.durable(ORDER_EVENTS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding orderEventsBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderEventsQueue)
                .to(orderEventsExchange)
                .with("order.state.*");
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange());
    }

    // --- Payment events topology ---

    @Bean
    public TopicExchange paymentEventsExchange() {
        return new TopicExchange(PAYMENT_EVENTS_EXCHANGE);
    }

    @Bean
    public FanoutExchange paymentDeadLetterExchange() {
        return new FanoutExchange(PAYMENT_EVENTS_DLX);
    }

    @Bean
    public Queue paymentEventsQueue() {
        return QueueBuilder.durable(PAYMENT_EVENTS_QUEUE)
                .withArgument("x-dead-letter-exchange", PAYMENT_EVENTS_DLX)
                .build();
    }

    @Bean
    public Queue paymentDeadLetterQueue() {
        return QueueBuilder.durable(PAYMENT_EVENTS_DLQ).build();
    }

    @Bean
    public Binding paymentEventsBinding(Queue paymentEventsQueue, TopicExchange paymentEventsExchange) {
        return BindingBuilder.bind(paymentEventsQueue)
                .to(paymentEventsExchange)
                .with("payment.*");
    }

    @Bean
    public Binding paymentDeadLetterBinding(Queue paymentDeadLetterQueue, FanoutExchange paymentDeadLetterExchange) {
        return BindingBuilder.bind(paymentDeadLetterQueue)
                .to(paymentDeadLetterExchange);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 2.0, 10000)
                .recoverer((args, cause) -> {
                    log.error("RabbitMQ message processing failed after 3 retries: {}", cause.getMessage());
                    throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(
                            "Exhausted retries — routing to DLQ", cause);
                })
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAdviceChain(retryInterceptor());
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
