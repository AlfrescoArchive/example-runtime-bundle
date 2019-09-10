package org.activiti.cloud.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.activiti.api.process.model.ProcessInstance;
import org.activiti.api.process.model.builders.StartProcessPayloadBuilder;
import org.activiti.api.process.model.payloads.StartProcessPayload;
import org.activiti.api.process.runtime.ProcessRuntime;
import org.activiti.api.runtime.shared.security.SecurityManager;
import org.activiti.cloud.api.process.model.IntegrationRequest;
import org.activiti.cloud.api.process.model.IntegrationResult;
import org.activiti.cloud.connectors.starter.channels.IntegrationResultSender;
import org.activiti.cloud.connectors.starter.configuration.ConnectorProperties;
import org.activiti.cloud.connectors.starter.model.IntegrationResultBuilder;
import org.activiti.core.common.spring.identity.ExtendedInMemoryUserDetailsManager;
import org.activiti.engine.RuntimeService;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
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
    private ProcessRuntime processRuntime;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TestSecurityUtil securityUtil;
    
    private static CountDownLatch exampleConnectorConsumer;
    
    @Before
    public void setUp() {
        exampleConnectorConsumer = new CountDownLatch(1);
    }

    @Test
    public void testExampleConnectorProcess() throws InterruptedException {
        // given 
        securityUtil.logInAs("user");
        
        String businessKey = BUSINESS_KEY;
        StartProcessPayload payload = new StartProcessPayloadBuilder().withProcessDefinitionKey(CONNECTOR_PROCESS)
                                                                      .withBusinessKey(businessKey)
                                                                      .build();                
        
        // when
        ProcessInstance processInstance = processRuntime.start(payload);
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
        IntegrationResultSender integrationResultSender;
        
        @Autowired
        private ConnectorProperties connectorProperties;        
        
        @StreamListener(ExampleConnectorChannels.IMPLEMENTATION)
        public void perfromTask(IntegrationRequest event) throws JsonParseException, JsonMappingException, IOException {
            
            Map<String, Object> results = Collections.singletonMap("result",
                                                                   event.getIntegrationContext()
                                                                        .getBusinessKey());
            
            Message<IntegrationResult> message = IntegrationResultBuilder.resultFor(event, connectorProperties)
                                                                         .withOutboundVariables(results)
                                                                         .buildMessage();
            integrationResultSender.send(message);            

            assertThat(results).containsEntry("result", BUSINESS_KEY);
            
            exampleConnectorConsumer.countDown();
        }
    }
    
    @TestConfiguration
    public static class TestSupportConfig {

        @Bean
        public UserDetailsService myUserDetailsService() {
            ExtendedInMemoryUserDetailsManager extendedInMemoryUserDetailsManager = new ExtendedInMemoryUserDetailsManager();

            List<GrantedAuthority> userAuthorities = new ArrayList<>();
            userAuthorities.add(new SimpleGrantedAuthority("ROLE_ACTIVITI_USER"));
            userAuthorities.add(new SimpleGrantedAuthority("GROUP_activitiTeam"));

            extendedInMemoryUserDetailsManager.createUser(new User("user",
                                                                   "password",
                                                                   userAuthorities));


            List<GrantedAuthority> adminAuthorities = new ArrayList<>();
            adminAuthorities.add(new SimpleGrantedAuthority("ROLE_ACTIVITI_ADMIN"));

            extendedInMemoryUserDetailsManager.createUser(new User("admin",
                                                                   "password",
                                                                   adminAuthorities));

            List<GrantedAuthority> garthAuthorities = new ArrayList<>();
            garthAuthorities.add(new SimpleGrantedAuthority("ROLE_ACTIVITI_USER"));
            garthAuthorities.add(new SimpleGrantedAuthority("GROUP_doctor"));

            extendedInMemoryUserDetailsManager.createUser(new User("garth",
                                                                   "password",
                                                                   garthAuthorities));

            //dean has role but no group
            List<GrantedAuthority> deanAuthorities = new ArrayList<>();
            deanAuthorities.add(new SimpleGrantedAuthority("ROLE_ACTIVITI_USER"));
            extendedInMemoryUserDetailsManager.createUser(new User("dean",
                                                                   "password",
                                                                   deanAuthorities));

            return extendedInMemoryUserDetailsManager;
        }
    }
    
    @TestConfiguration
    public static class TestSecurityUtil {

        private Logger logger = LoggerFactory.getLogger(TestSecurityUtil.class);

        @Autowired
        private UserDetailsService userDetailsService;

        @Autowired
        private SecurityManager securityManager;

        public void logInAs(String username) {

            UserDetails user = userDetailsService.loadUserByUsername(username);
            if (user == null) {
                throw new IllegalStateException("User " + username + " doesn't exist, please provide a valid user");
            }
            logger.info("> Logged in as: " + username);
            SecurityContextHolder.setContext(new SecurityContextImpl(new Authentication() {
                @Override
                public Collection<? extends GrantedAuthority> getAuthorities() {
                    return user.getAuthorities();
                }

                @Override
                public Object getCredentials() {
                    return user.getPassword();
                }

                @Override
                public Object getDetails() {
                    return user;
                }

                @Override
                public Object getPrincipal() {
                    return user;
                }

                @Override
                public boolean isAuthenticated() {
                    return true;
                }

                @Override
                public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {

                }

                @Override
                public String getName() {
                    return user.getUsername();
                }
            }));
            org.activiti.engine.impl.identity.Authentication.setAuthenticatedUserId(username);

            assertThat(securityManager.getAuthenticatedUserId()).isEqualTo(username);
        }
    }
}