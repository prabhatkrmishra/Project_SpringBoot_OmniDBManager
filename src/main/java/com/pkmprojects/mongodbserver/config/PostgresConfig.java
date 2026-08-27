package com.pkmprojects.mongodbserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresConfig {

    @Bean
    DataSource postgresDataSource(
            @Value("${app.postgres.uri:jdbc:postgresql://127.0.0.1:9813/postgres}") String uri,
            @Value("${POSTGRES_ROOT_USER:root}") String username,
            @Value("${POSTGRES_ROOT_PASSWORD:root}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(uri);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    JdbcTemplate postgresJdbcTemplate(DataSource postgresDataSource) {
        return new JdbcTemplate(postgresDataSource);
    }
}
