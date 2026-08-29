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
@ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
public class MysqlConfig {

    private static final Logger log = LoggerFactory.getLogger(MysqlConfig.class);

    @Bean
    DataSource mysqlDataSource(
            @Value("${app.mysql.uri:jdbc:mysql://127.0.0.1:9816/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000}") String uri,
            @Value("${MYSQL_ROOT_PASSWORD:root}") String password) {
        log.info("MysqlConfig: Creating Hikari mysqlDataSource with uri={}", uri);
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(uri);
        // MySQL superuser is always `root` — MYSQL_ROOT_USER has no container effect
        ds.setUsername("root");
        ds.setPassword(password);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
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
    JdbcTemplate mysqlJdbcTemplate(@Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        log.info("MysqlConfig: Creating mysqlJdbcTemplate");
        JdbcTemplate tpl = new JdbcTemplate(mysqlDataSource);
        tpl.setQueryTimeout(5);
        return tpl;
    }
}
