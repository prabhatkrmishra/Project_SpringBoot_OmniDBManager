package com.pkmprojects.mongodbserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;

/**
 * Entry point for the MongoDB database provisioning service (control plane).
 *
 * <p>Boots the embedded web server and enables scanning of
 * {@code @ConfigurationProperties} records such as {@link
 * com.pkmprojects.mongodbserver.config.AdminProperties}. Admin credentials come
 * from {@code APP_ADMIN_USERNAME} / {@code APP_ADMIN_PASSWORD} in {@code .env}.</p>
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
