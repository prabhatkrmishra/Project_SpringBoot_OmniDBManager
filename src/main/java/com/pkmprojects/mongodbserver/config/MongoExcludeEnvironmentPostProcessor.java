package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes Mongo auto-configuration truly conditional on {@code app.mongo.enabled}.
 * When Mongo is disabled (default) the Mongo driver beans are never created, so
 * the app boots and runs with the in-memory metadata stores. When enabled the
 * standard Boot Mongo auto-configs apply.
 */
public class MongoExcludeEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROP = "app.mongo.enabled";
    private static final String EXCLUDE_PROP = "spring.autoconfigure.exclude";

    private static final String[] MONGO_EXCLUDES = {
            "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
            "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration",
            "org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration",
            "org.springframework.boot.mongodb.autoconfigure.health.MongoHealthContributorAutoConfiguration",
            "org.springframework.boot.mongodb.autoconfigure.metrics.MongoMetricsAutoConfiguration"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, org.springframework.boot.SpringApplication application) {
        boolean mongoEnabled = environment.getProperty(PROP, Boolean.class, false);
        if (mongoEnabled) {
            return;
        }
        String existing = environment.getProperty(EXCLUDE_PROP, "");
        String joined = String.join(",", MONGO_EXCLUDES);
        String merged = existing == null || existing.isBlank() ? joined : existing + "," + joined;

        Map<String, Object> map = new LinkedHashMap<>();
        map.put(EXCLUDE_PROP, merged);
        environment.getPropertySources().addFirst(new MapPropertySource("mongoConditionalExclude", map));
    }
}
