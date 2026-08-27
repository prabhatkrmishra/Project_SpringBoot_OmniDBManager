package com.pkmprojects.mongodbserver.error;

/**
 * Thrown when a MongoDB driver operation fails during provisioning or exploration
 * (maps to HTTP 500). The message is kept generic - driver details go to the log,
 * never to the browser.
 */
public class ProvisioningException extends RuntimeException {

    public ProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProvisioningException(String message) {
        super(message);
    }
}
