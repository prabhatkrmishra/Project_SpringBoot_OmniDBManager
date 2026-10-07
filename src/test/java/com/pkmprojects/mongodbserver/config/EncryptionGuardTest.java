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
 * Plaintext tenant-password storage must never be silent. Warn always, and
 * refuse to start whenever {@code APP_ENCRYPTION_ENFORCE} is on (the default)
 * or the {@code atlas} profile is active.
 */
@ExtendWith(MockitoExtension.class)
class EncryptionGuardTest {

    @Mock
    private Environment environment;

    @Mock
    private EncryptionService encryptionService;

    private EncryptionGuard guard(boolean enforce) {
        return new EncryptionGuard(encryptionService, new EncryptionProperties(null, enforce), environment);
    }

    // ── key present: nothing changes, whatever the flags say ───────────

    @Test
    void enabledEncryptionStartsQuietly() {
        when(encryptionService.isEnabled()).thenReturn(true);
        assertThatCode(() -> guard(true).run(null)).doesNotThrowAnyException();
    }

    @Test
    void enabledEncryptionStartsQuietlyEvenWhenEnforcementIsOff() {
        when(encryptionService.isEnabled()).thenReturn(true);
        assertThatCode(() -> guard(false).run(null)).doesNotThrowAnyException();
    }

    // ── key absent: the default now refuses to start ──────────────────

    @Test
    void disabledEncryptionFailsFastWhenEnforced() {
        // The documented deployment runs with no Spring profile at all, so this
        // -- not the atlas profile below -- is what actually protects a VPS that
        // never set APP_ENCRYPTION_KEY.
        when(encryptionService.isEnabled()).thenReturn(false);
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(false);
        assertThatThrownBy(() -> guard(true).run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureMessageNeverContainsKeyMaterial() {
        when(encryptionService.isEnabled()).thenReturn(false);
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(false);
        assertThatThrownBy(() -> guard(true).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENCRYPTION_KEY")
                .hasMessageNotContaining("null");
    }

    @Test
    void disabledEncryptionFailsFastUnderAtlasProfile() {
        when(encryptionService.isEnabled()).thenReturn(false);
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(true);
        assertThatThrownBy(() -> guard(false).run(null)).isInstanceOf(IllegalStateException.class);
    }

    // ── key absent and enforcement explicitly waived: the old behaviour ─

    @Test
    void disabledEncryptionWarnsButStartsWhenEnforcementWaived() {
        when(encryptionService.isEnabled()).thenReturn(false);
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(false);
        assertThatCode(() -> guard(false).run(null)).doesNotThrowAnyException();
        verify(environment).acceptsProfiles(Profiles.of("atlas"));
    }
}
