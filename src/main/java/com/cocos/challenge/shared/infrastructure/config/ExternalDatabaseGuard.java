package com.cocos.challenge.shared.infrastructure.config;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Safety checks for the external database provided by Cocos ({@code cocos} profile).
 * <p>
 * Runs as a {@link BeanFactoryPostProcessor}, i.e. before any regular bean (in particular the DataSource) is created,
 * so a misconfiguration fails fast without opening a connection:
 * <ul>
 *     <li>the JDBC URL must be present and must not embed credentials: they belong in COCOS_DB_USERNAME /
 *     COCOS_DB_PASSWORD, and a URL with credentials is easily leaked through logs or error messages;</li>
 *     <li>logs whether the connection is read-only (default) or writes were explicitly enabled.</li>
 * </ul>
 * Neither the URL nor any credential is ever logged.
 */
@Configuration(proxyBeanMethods = false)
@Profile("cocos")
public class ExternalDatabaseGuard {

    private static final Logger log = LoggerFactory.getLogger(ExternalDatabaseGuard.class);

    /** user[:password]@ in the authority, or user= / password= parameters (pgjdbc accepts both). */
    private static final Pattern EMBEDDED_CREDENTIALS =
            Pattern.compile("(?i)(//[^/?#]*@)|([?&;](user|password)=)");

    @Bean
    static BeanFactoryPostProcessor externalDatabaseSafetyCheck(Environment environment) {
        return beanFactory -> {
            String url;
            try {
                url = environment.getProperty("spring.datasource.url");
            } catch (IllegalArgumentException unresolvedPlaceholder) {
                throw new IllegalStateException(
                        "Profile 'cocos' requires COCOS_DB_URL, COCOS_DB_USERNAME and COCOS_DB_PASSWORD");
            }
            verifyUrl(url);
            boolean readOnly = environment.getProperty("spring.datasource.hikari.read-only", Boolean.class, true);
            if (readOnly) {
                log.info("Profile 'cocos': external Cocos database in READ-ONLY mode");
            } else {
                log.warn("Profile 'cocos': WRITES ENABLED on the external Cocos database (COCOS_DB_READ_ONLY=false)");
            }
        };
    }

    static void verifyUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Profile 'cocos' requires COCOS_DB_URL");
        }
        if (EMBEDDED_CREDENTIALS.matcher(url).find()) {
            throw new IllegalStateException("COCOS_DB_URL must not contain credentials; "
                    + "use COCOS_DB_USERNAME and COCOS_DB_PASSWORD instead");
        }
    }
}
