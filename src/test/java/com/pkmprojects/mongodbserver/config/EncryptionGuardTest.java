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

    private static final String BASE64_KEY =
            java.util.Base64.getEncoder().encodeToString(new byte[32]);

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

    // ── through the real Spring binder, not a hand-built bean ──────────
    //
    // These are the tests that would have caught the original bug. A second
    // constructor on the record made the binder fall back to JavaBean
    // instantiation and fail with "No default constructor found", which took the
    // whole application down. Constructing EncryptionProperties directly, as the
    // other tests here do, never touches that path.

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(EncryptionProperties.class)
    static class EncryptionWiring {
        @org.springframework.context.annotation.Bean
        EncryptionService encryptionService(EncryptionProperties p) {
            return new EncryptionService(p);
        }

        @org.springframework.context.annotation.Bean
        EncryptionGuard encryptionGuard(EncryptionService s, EncryptionProperties p, Environment e) {
            return new EncryptionGuard(s, p, e);
        }
    }

    @Test
    void propertiesBindThroughTheRealBinder() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(EncryptionWiring.class)
                .withPropertyValues("app.encryption.key=" + BASE64_KEY,
                        "app.encryption.enforce=true")
                .run(ctx -> {
                    org.assertj.core.api.Assertions.assertThat(ctx).hasNotFailed();
                    org.assertj.core.api.Assertions
                            .assertThat(ctx.getBean(EncryptionProperties.class).enforce())
                            .isTrue();
                    org.assertj.core.api.Assertions
                            .assertThat(ctx.getBean(EncryptionService.class).isEnabled())
                            .isTrue();
                });
    }

    @Test
    void boundPropertiesDriveTheGuard() {
        // Bound with no key, so encryption is off and the guard must refuse.
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(EncryptionWiring.class)
                .withPropertyValues("app.encryption.key=", "app.encryption.enforce=true")
                .run(ctx -> {
                    org.assertj.core.api.Assertions.assertThat(ctx).hasNotFailed();
                    var guard = ctx.getBean(EncryptionGuard.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.run(null))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("APP_ENCRYPTION_KEY");
                });
    }

    @Test
    void boundKeyLetsTheGuardStartQuietly() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(EncryptionWiring.class)
                .withPropertyValues("app.encryption.key=" + BASE64_KEY)
                .run(ctx -> {
                    org.assertj.core.api.Assertions.assertThat(ctx).hasNotFailed();
                    var guard = ctx.getBean(EncryptionGuard.class);
                    org.assertj.core.api.Assertions.assertThatCode(() -> guard.run(null))
                            .doesNotThrowAnyException();
                });
    }
}
