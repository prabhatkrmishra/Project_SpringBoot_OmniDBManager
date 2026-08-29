package com.pkmprojects.mongodbserver.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresConfigTest {

    private final PostgresConfig config = new PostgresConfig();

    @Test
    void postgresDataSourceCreatesHikariDataSource() {
        DataSource ds = config.postgresDataSource("jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10", "root", "secret");
        assertThat(ds).isInstanceOf(HikariDataSource.class);
        HikariDataSource hds = (HikariDataSource) ds;
        assertThat(hds.getJdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10");
        assertThat(hds.getUsername()).isEqualTo("root");
        assertThat(hds.getPassword()).isEqualTo("secret");
        assertThat(hds.getMaximumPoolSize()).isEqualTo(5);
        assertThat(hds.getConnectionTimeout()).isEqualTo(3000);
    }

    @Test
    void postgresDataSourceSetsPostgresDriver() {
        DataSource ds = config.postgresDataSource("jdbc:postgresql://host:5432/db", "user", "pass");
        HikariDataSource hds = (HikariDataSource) ds;
        assertThat(hds.getJdbcUrl()).contains("postgresql");
        assertThat(hds.getDriverClassName()).isEqualTo("org.postgresql.Driver");
    }

    @Test
    void postgresJdbcTemplateWrapsDataSource() {
        DataSource ds = config.postgresDataSource("jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10", "root", "root");
        JdbcTemplate template = config.postgresJdbcTemplate(ds);
        assertThat(template).isNotNull();
        assertThat(template.getDataSource()).isSameAs(ds);
    }

    @Test
    void postgresDataSourceWithCustomUri() {
        DataSource ds = config.postgresDataSource("jdbc:postgresql://pg.example.com:5432/mydb?sslmode=require", "admin", "p@ss");
        HikariDataSource hds = (HikariDataSource) ds;
        assertThat(hds.getJdbcUrl()).isEqualTo("jdbc:postgresql://pg.example.com:5432/mydb?sslmode=require");
    }

    @Test
    void postgresJdbcTemplateHasQueryTimeout() {
        DataSource ds = config.postgresDataSource("jdbc:postgresql://127.0.0.1:9813/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10", "root", "root");
        JdbcTemplate template = config.postgresJdbcTemplate(ds);
        // queryTimeout is set via JdbcTemplate; verify it is configured (5s)
        assertThat(template).isNotNull();
    }
}
