package com.pkmprojects.mongodbserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes engine availability (.env controlled) to every Thymeleaf view.
 * Postgres/MySQL are optional and <strong>off by default</strong>
 * ({@code app.postgres.enabled=false}, {@code app.mysql.enabled=false});
 * they become visible only when {@code POSTGRES_ENABLED=true} /
 * {@code MYSQL_ENABLED=true} is set in {@code .env}. MongoDB is the
 * metadata store and always enabled. Views use
 * {@code postgresEnabled/mysqlEnabled/mongoEnabled} to hide cards,
 * sections and nav links for disabled engines; if all optional engines
 * are hidden the dashboard still renders correctly.
 */
@ControllerAdvice
public class EngineAvailabilityAdvice {

    private final boolean mongoEnabled;
    private final boolean postgresEnabled;
    private final boolean mysqlEnabled;

    public EngineAvailabilityAdvice(
            @Value("${app.mongo.enabled:false}") boolean mongoEnabled,
            @Value("${app.postgres.enabled:false}") boolean postgresEnabled,
            @Value("${app.mysql.enabled:false}") boolean mysqlEnabled) {
        this.mongoEnabled = mongoEnabled;
        this.postgresEnabled = postgresEnabled;
        this.mysqlEnabled = mysqlEnabled;
    }

    @ModelAttribute("mongoEnabled")
    public boolean mongoEnabled() {
        return mongoEnabled;
    }

    @ModelAttribute("postgresEnabled")
    public boolean postgresEnabled() {
        return postgresEnabled;
    }

    @ModelAttribute("mysqlEnabled")
    public boolean mysqlEnabled() {
        return mysqlEnabled;
    }
}
