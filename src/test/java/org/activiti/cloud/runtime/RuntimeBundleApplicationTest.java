package org.activiti.cloud.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.activiti.cloud.api.process.model.IntegrationRequest;
import org.activiti.cloud.api.process.model.IntegrationResult;
import org.activiti.cloud.api.process.model.impl.IntegrationResultImpl;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.runtime.ProcessInstance;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.cloud.stream.binding.BinderAwareChannelResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
/**
 * See: https://cloud.spring.io/spring-cloud-static/spring-cloud-stream/2.1.0.RELEASE/multi/multi__testing.html#spring_integration_test_binder
 */
@SpringBootTest(classes = {RuntimeBundleApplication.class, TestChannelBinderConfiguration.class})
public class RuntimeBundleApplicationTest {

    private static final String CONNECTOR_PROCESS = "ConnectorProcess";
    private static final String BUSINESS_KEY = "businessKey";

    @Autowired
    private RuntimeService runtimeService;

    private static CountDownLatch exampleConnectorConsumer;
    
    @Before
    public void setUp() {
        exampleConnectorConsumer = new CountDownLatch(1);
    }

    @Test
    public void testExampleConnectorProcess() throws InterruptedException {
        // given 
        String businessKey = BUSINESS_KEY;
        
        // when
        ProcessInstance processInstance = runtimeService.createProcessInstanceBuilder()
                                                        .processDefinitionKey(CONNECTOR_PROCESS)
                                                        .businessKey(businessKey)
                                                        .start();
        // then
        assertThat(processInstance).as("Should start process instance")
                                   .isNotNull();
        // and
        assertThat(exampleConnectorConsumer.await(1, TimeUnit.SECONDS))
                                           .as("Should execute cloud connector task")
                                           .isTrue();
        // and
        Awaitility.await().untilAsserted(() -> {
            assertThat(runtimeService.createProcessInstanceQuery()
                                     .processDefinitionKey(CONNECTOR_PROCESS)
                                     .active()
                                     .list())
                                     .as("Should complete process")
                                     .isEmpty();
        });
    }

    /**
     * Define Connector Mock Channel  
     */
    public static interface ExampleConnectorChannels {
        String IMPLEMENTATION = "exampleConnectorConsumer";

        @Input(IMPLEMENTATION)      
        SubscribableChannel input();
    }
    
    /**
     * Define Mock Connector Consumer  
     */
    @EnableBinding(ExampleConnectorChannels.class)
    public static class ExampleConnectorConsumer {
        
        @Autowired
        TestIntegrationResultSender integrationResultSender;
        
        @StreamListener(ExampleConnectorChannels.IMPLEMENTATION)
        public void perfromTask(IntegrationRequest event) throws JsonParseException, JsonMappingException, IOException {
            
            Map<String, Object> result = Collections.singletonMap("result",
                                                                  event.getIntegrationContext()
                                                                  .getBusinessKey());
            event.getIntegrationContext()
                 .addOutBoundVariables(result);

            IntegrationResult integrationResult = new IntegrationResultImpl(event, 
                                                                            event.getIntegrationContext());       
            
            integrationResultSender.send(integrationResult, ExampleConnectorChannels.IMPLEMENTATION);
            
            assertThat(result).containsEntry("result", BUSINESS_KEY);
            
            exampleConnectorConsumer.countDown();
        }
    }
    
    @TestConfiguration
    public static class TestSupportConfig {

        @Bean
        public TestIntegrationResultSender testIntegrationResultSender() {
            return new TestIntegrationResultSender();
        }
        
    }
    
    public static class TestIntegrationResultSender {
        
        @Autowired
        private BinderAwareChannelResolver resolver;

        @Autowired
        private ObjectMapper objectMapper;
        
        public void send(IntegrationResult integrationResult, String targetService) throws JsonProcessingException {
            
            Message<String> message = MessageBuilder.withPayload(objectMapper.writeValueAsString(integrationResult))
                                                               .setHeader("targetService", targetService)
                                                               .build();

            resolver.resolveDestination("integrationResultsConsumer")
                    .send(message);
        }
    }
}