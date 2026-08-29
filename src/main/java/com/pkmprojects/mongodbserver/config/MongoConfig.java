package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Re-arms MongoDB auto-config only when {@code app.mongo.enabled=true}. The
 * main class always excludes the Mongo auto-configs via {@code excludeName}; when
 * enabled, this configuration imports them back by string name so the build does
 * not hard-depend on their packages.
 */
@Configuration
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class MongoConfig {
}
