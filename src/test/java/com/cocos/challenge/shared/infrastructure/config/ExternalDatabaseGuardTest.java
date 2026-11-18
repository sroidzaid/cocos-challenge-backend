package com.cocos.challenge.shared.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalDatabaseGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db.example.com:5432/cocos",
            "jdbc:postgresql://db.example.com/cocos?sslmode=require",
            "jdbc:postgresql://10.0.0.1:5432/cocos?ssl=true&currentSchema=public"
    })
    void acceptsUrlsWithoutCredentials(String url) {
        assertThatCode(() -> ExternalDatabaseGuard.verifyUrl(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://admin:secret@db.example.com:5432/cocos",
            "jdbc:postgresql://admin@db.example.com:5432/cocos",
            "jdbc:postgresql://db.example.com/cocos?user=admin&password=secret",
            "jdbc:postgresql://db.example.com/cocos?sslmode=require&PASSWORD=secret"
    })
    void rejectsUrlsWithEmbeddedCredentialsWithoutEchoingThem(String url) {
        assertThatThrownBy(() -> ExternalDatabaseGuard.verifyUrl(url))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("admin");
    }

    @Test
    void rejectsMissingUrl() {
        assertThatThrownBy(() -> ExternalDatabaseGuard.verifyUrl(" ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenTheEnvironmentVariablesAreNotDefined() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "${COCOS_DB_URL}");
        BeanFactoryPostProcessor check = ExternalDatabaseGuard.externalDatabaseSafetyCheck(environment);

        assertThatThrownBy(() -> check.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COCOS_DB_URL");
    }

    @Test
    void passesWithACleanUrl() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com/cocos");
        BeanFactoryPostProcessor check = ExternalDatabaseGuard.externalDatabaseSafetyCheck(environment);

        assertThatCode(() -> check.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .doesNotThrowAnyException();
        assertThat(environment.getProperty("spring.datasource.hikari.read-only", Boolean.class, true)).isTrue();
    }
}
