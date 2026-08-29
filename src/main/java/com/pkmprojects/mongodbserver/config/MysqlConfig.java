package com.pkmprojects.mongodbserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
public class MysqlConfig {

    private static final Logger log = LoggerFactory.getLogger(MysqlConfig.class);

    @Bean
    DataSource mysqlDataSource(
            @Value("${app.mysql.uri:jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}") String uri,
            @Value("${MYSQL_ROOT_PASSWORD:root}") String password) {
        log.info("MysqlConfig: Creating mysqlDataSource with uri={}", uri);
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(uri);
        // MySQL superuser is always `root` — MYSQL_ROOT_USER has no container effect
        ds.setUsername("root");
        ds.setPassword(password);
        return ds;
    }

    @Bean
    JdbcTemplate mysqlJdbcTemplate(@Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        log.info("MysqlConfig: Creating mysqlJdbcTemplate");
        return new JdbcTemplate(mysqlDataSource);
    }
}
