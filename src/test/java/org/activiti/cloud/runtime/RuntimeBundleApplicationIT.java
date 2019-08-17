package org.activiti.cloud.runtime;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = RuntimeBundleApplication.class,
                properties = "spring.activiti.process-definition-location-prefix=file:/processes/")
@DirtiesContext
public class RuntimeBundleApplicationIT {

    @Test
    public void contextLoads() throws Exception {

    }
}