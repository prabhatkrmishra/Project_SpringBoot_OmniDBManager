package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.Ordered;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes Mongo auto-configuration truly conditional on {@code app.mongo.enabled}
 * and normalizes blank URI vars so {@code VAR=} (empty) does not break the
 * {@code ${VAR:default}} fallbacks in {@code application.yml} — empty strings
 * are treated as absent.
 *
 * <p>Runs with {@link Ordered#LOWEST_PRECEDENCE} so it executes after
 * {@code springboot4-dotenv} and other environment post-processors that may
 * populate properties from {@code .env}.</p>
 */
public class MongoExcludeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MongoExcludeEnvironmentPostProcessor.class);
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
        log.info("MongoExcludeEnvironmentPostProcessor: app.mongo.enabled={}", mongoEnabled);
        if (mongoEnabled) {
            log.info("MongoExcludeEnvironmentPostProcessor: Mongo enabled, skipping excludes");
            return;
        }
        String existing = environment.getProperty(EXCLUDE_PROP, "");
        String joined = String.join(",", MONGO_EXCLUDES);
        String merged = existing == null || existing.isBlank() ? joined : existing + "," + joined;
        log.info("MongoExcludeEnvironmentPostProcessor: setting spring.autoconfigure.exclude={}", merged);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put(EXCLUDE_PROP, merged);
        environment.getPropertySources().addFirst(new MapPropertySource("mongoConditionalExclude", map));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
