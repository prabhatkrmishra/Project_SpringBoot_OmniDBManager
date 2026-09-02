package com.pkmprojects.mongodbserver.config;

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
 * Unit tests for the startup guard that rejects default admin credentials
 * (and fails fast under the {@code atlas} profile).
 */
@ExtendWith(MockitoExtension.class)
class AdminCredentialsGuardTest {

    @Mock
    private Environment environment;

    @Test
    void nonDefaultCredentialsAreAccepted() {
        AdminCredentialsGuard guard = new AdminCredentialsGuard(new AdminProperties("bob", "s3cret!", false), environment);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void defaultCredentialsFailFastUnderAtlasProfile() {
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(true);
        AdminCredentialsGuard guard = new AdminCredentialsGuard(new AdminProperties("admin", "admin", false), environment);

        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
        verify(environment).acceptsProfiles(Profiles.of("atlas"));
    }

    @Test
    void defaultCredentialsWarnButStartWithoutAtlasProfile() {
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(false);
        AdminCredentialsGuard guard = new AdminCredentialsGuard(new AdminProperties("admin", "admin", false), environment);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
        verify(environment).acceptsProfiles(Profiles.of("atlas"));
    }

    @Test
    void loneDefaultPasswordIsStillGuardedUnderAtlasProfile() {
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(true);
        AdminCredentialsGuard guard = new AdminCredentialsGuard(new AdminProperties("prodadmin", "admin", false), environment);

        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
        verify(environment).acceptsProfiles(Profiles.of("atlas"));
    }

    @Test
    void loneDefaultUsernameIsStillGuardedUnderAtlasProfile() {
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(true);
        AdminCredentialsGuard guard = new AdminCredentialsGuard(new AdminProperties("admin", "s3cret!", false), environment);

        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
        verify(environment).acceptsProfiles(Profiles.of("atlas"));
    }

    @Test
    void defaultCredentialsFailFastWhenEnforcementIsForcedWithoutAtlasProfile() {
        when(environment.acceptsProfiles(Profiles.of("atlas"))).thenReturn(false);
        AdminCredentialsGuard guard = new AdminCredentialsGuard(new AdminProperties("admin", "admin", true), environment);

        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
        verify(environment).acceptsProfiles(Profiles.of("atlas"));
    }

    @Test
    void customCredentialsAreAcceptedEvenWhenEnforcementIsForced() {
        AdminCredentialsGuard guard = new AdminCredentialsGuard(new AdminProperties("bob", "s3cret!", true), environment);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }
}
