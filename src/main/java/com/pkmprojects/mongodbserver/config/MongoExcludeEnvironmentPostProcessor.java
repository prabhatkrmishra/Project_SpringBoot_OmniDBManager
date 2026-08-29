package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes Mongo auto-configuration truly conditional on {@code app.mongo.enabled}
 * and normalizes blank URI vars so {@code VAR=} (empty) does not break the
 * {@code ${VAR:default}} fallbacks in {@code application.yml} — empty strings
 * are treated as absent.
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

    private static final String[] BLANK_AS_ABSENT = {
            "MONGODB_URI", "POSTGRES_URI", "MYSQL_URI",
            "MONGODB_ROOT_USERNAME", "MONGODB_ROOT_PASSWORD",
            "POSTGRES_ROOT_USER", "POSTGRES_ROOT_PASSWORD",
            "MYSQL_ROOT_PASSWORD"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, org.springframework.boot.SpringApplication application) {
        // Normalize `VAR=` (empty) to absent so `${VAR:default}` fallbacks in application.yml work
        for (String key : BLANK_AS_ABSENT) {
            String val = environment.getProperty(key);
            if (val != null && val.isBlank()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(key, null); // null + containsProperty==true => resolver uses default
                environment.getPropertySources().addFirst(new MapPropertySource("normalize-" + key, m));
                // Spring will now resolve ${MONGODB_URI:default} to default when .env has MONGODB_URI=
            }
        }

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
