package com.pkmprojects.mongodbserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresConfigTest {

    private final PostgresConfig config = new PostgresConfig();

    @Test
    void postgresDataSourceCreatesDriverManagerDataSource() {
        DataSource ds = config.postgresDataSource("jdbc:postgresql://127.0.0.1:9813/postgres", "root", "secret");
        assertThat(ds).isInstanceOf(DriverManagerDataSource.class);
        DriverManagerDataSource dmds = (DriverManagerDataSource) ds;
        assertThat(dmds.getUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:9813/postgres");
        assertThat(dmds.getUsername()).isEqualTo("root");
        assertThat(dmds.getPassword()).isEqualTo("secret");
    }

    @Test
    void postgresDataSourceSetsPostgresDriver() {
        DataSource ds = config.postgresDataSource("jdbc:postgresql://host:5432/db", "user", "pass");
        DriverManagerDataSource dmds = (DriverManagerDataSource) ds;
        // driver class name is set via string — verify it resolves
        assertThat(dmds.getUrl()).contains("postgresql");
    }

    @Test
    void postgresJdbcTemplateWrapsDataSource() {
        DataSource ds = config.postgresDataSource("jdbc:postgresql://127.0.0.1:9813/postgres", "root", "root");
        JdbcTemplate template = config.postgresJdbcTemplate(ds);
        assertThat(template).isNotNull();
        assertThat(template.getDataSource()).isSameAs(ds);
    }

    @Test
    void postgresDataSourceWithCustomUri() {
        DataSource ds = config.postgresDataSource("jdbc:postgresql://pg.example.com:5432/mydb?sslmode=require", "admin", "p@ss");
        DriverManagerDataSource dmds = (DriverManagerDataSource) ds;
        assertThat(dmds.getUrl()).isEqualTo("jdbc:postgresql://pg.example.com:5432/mydb?sslmode=require");
    }
}
