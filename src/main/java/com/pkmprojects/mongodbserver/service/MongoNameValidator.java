package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Validates MongoDB database/collection/user names. Names also travel in URL
 * paths, so the allowed charset is deliberately restricted to URL-safe ASCII.
 * @deprecated Use {@link DatabaseNameValidator} — kept for backward compat.
 */
@Component
public class MongoNameValidator extends DatabaseNameValidator {

    /**
     * Databases managed by MongoDB itself or by this application; never user-manageable.
     */
    static final Set<String> SYSTEM_DATABASES = DatabaseNameValidator.SYSTEM_DATABASES;
}
