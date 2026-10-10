package cn.bugstack.chatgpt.data.config;

import cn.bugstack.chatgpt.data.mq.PaymentEventRocketMqConsumer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Native RocketMQ lifecycle. The producer and consumer are only started when
 * the feature flag is enabled; the default test profile is broker independent.
 */
@Configuration
@EnableConfigurationProperties(PaymentEventRocketMqProperties.class)
@ConditionalOnProperty(prefix = "payment.events.rocketmq", name = "enabled", havingValue = "true")
public class PaymentEventRocketMqConfig {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer paymentEventProducer(PaymentEventRocketMqProperties properties) {
        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNamesrvAddr());
        return producer;
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer paymentEventConsumer(
            PaymentEventRocketMqProperties properties,
            PaymentEventRocketMqConsumer listener) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
        consumer.setNamesrvAddr(properties.getNamesrvAddr());
        consumer.subscribe(properties.getTopic(), "*");
        consumer.registerMessageListener(listener);
        return consumer;
    }
}
