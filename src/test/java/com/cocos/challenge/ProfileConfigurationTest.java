package com.cocos.challenge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the safety properties of the configuration files themselves (raw, unresolved values). The "cocos" profile
 * is never started by tests; its configuration is verified statically here.
 */
class ProfileConfigurationTest {

    @Test
    void commonConfigurationNeverModifiesTheSchemaAndHasNoConnection() throws IOException {
        PropertySource<?> common = load("application.yml");

        assertThat(common.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(common.getProperty("spring.jpa.open-in-view")).isEqualTo(false);
        assertThat(common.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(common.getProperty("spring.profiles.default")).isEqualTo("local");
        assertThat(common.getProperty("spring.datasource.url")).isNull();
        assertThat(common.getProperty("spring.datasource.password")).isNull();
    }

    @Test
    void cocosProfileRequiresCredentialsFromEnvironmentWithoutDefaults() throws IOException {
        PropertySource<?> cocos = load("application-cocos.yml");

        assertThat(cocos.getProperty("spring.datasource.url")).isEqualTo("${COCOS_DB_URL}");
        assertThat(cocos.getProperty("spring.datasource.username")).isEqualTo("${COCOS_DB_USERNAME}");
        assertThat(cocos.getProperty("spring.datasource.password")).isEqualTo("${COCOS_DB_PASSWORD}");
    }

    @Test
    void cocosProfileIsReadOnlyByDefaultAndRunsNoScripts() throws IOException {
        PropertySource<?> cocos = load("application-cocos.yml");

        assertThat(cocos.getProperty("spring.datasource.hikari.read-only")).isEqualTo("${COCOS_DB_READ_ONLY:true}");
        assertThat(cocos.getProperty("spring.datasource.hikari.data-source-properties.readOnlyMode"))
                .isEqualTo("always");
        assertThat(cocos.getProperty("spring.sql.init.mode")).as("must not override the common 'never'").isNull();
        assertThat(cocos.getProperty("spring.jpa.hibernate.ddl-auto")).as("must not override 'validate'").isNull();
    }

    @Test
    void localProfileDoesNotReferenceTheCocosDatabase() throws IOException {
        assertThat(read("application-local.yml")).doesNotContain("COCOS_");
        assertThat(load("application-local.yml").getProperty("spring.datasource.url"))
                .isEqualTo("${LOCAL_DB_URL:jdbc:postgresql://localhost:5432/cocos}");
    }

    private static PropertySource<?> load(String file) throws IOException {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).getFirst();
    }

    private static String read(String file) throws IOException {
        try (InputStream in = new ClassPathResource(file).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
