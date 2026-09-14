package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.service.EncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plaintext tenant-password storage must never be silent in a real
 * deployment — warn always, fail fast under the {@code atlas} profile.
 */
@ExtendWith(MockitoExtension.class)
class EncryptionGuardTest {

    @Mock
    private Environment environment;

    @Mock
    private EncryptionService encryptionService;

    @Test
    void enabledEncryptionStartsQuietly() {
        when(encryptionService.isEnabled()).thenReturn(true);
        EncryptionGuard guard = new EncryptionGuard(encryptionService, environment);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void disabledEncryptionFailsFastUnderAtlasProfile() {
        when(encryptionService.isEnabled()).thenReturn(false);
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(true);
        EncryptionGuard guard = new EncryptionGuard(encryptionService, environment);

        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledEncryptionWarnsButStartsWithoutAtlasProfile() {
        when(encryptionService.isEnabled()).thenReturn(false);
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(false);
        EncryptionGuard guard = new EncryptionGuard(encryptionService, environment);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
        verify(environment).acceptsProfiles(Profiles.of("atlas"));
    }
}
