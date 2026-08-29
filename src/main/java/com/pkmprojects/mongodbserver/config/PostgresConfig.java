package com.pkmprojects.mongodbserver.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(PostgresConfig.class);

    @Bean
    DataSource postgresDataSource(
            @Value("${app.postgres.uri:jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10}") String uri,
            @Value("${POSTGRES_ROOT_USER:root}") String username,
            @Value("${POSTGRES_ROOT_PASSWORD:root}") String password) {
        log.info("PostgresConfig: Creating Hikari postgresDataSource with uri={}, username={}", uri, username);
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(uri);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(3000);
        ds.setValidationTimeout(2000);
        ds.setIdleTimeout(30000);
        ds.setMaxLifetime(120000);
        ds.setLeakDetectionThreshold(10000);
        ds.setConnectionTestQuery("SELECT 1");
        return ds;
    }

    @Bean
    JdbcTemplate postgresJdbcTemplate(@Qualifier("postgresDataSource") DataSource postgresDataSource) {
        log.info("PostgresConfig: Creating postgresJdbcTemplate");
        JdbcTemplate tpl = new JdbcTemplate(postgresDataSource);
        tpl.setQueryTimeout(5);
        return tpl;
    }
}
