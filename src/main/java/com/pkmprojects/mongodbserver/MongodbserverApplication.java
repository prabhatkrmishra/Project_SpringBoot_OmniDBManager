package com.pkmprojects.mongodbserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;

/**
 * Entry point for the MongoDB database provisioning service (control plane).
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class MongodbserverApplication {

    public static void main(String[] args) {
        SpringApplication.run(MongodbserverApplication.class, args);
    }

}
