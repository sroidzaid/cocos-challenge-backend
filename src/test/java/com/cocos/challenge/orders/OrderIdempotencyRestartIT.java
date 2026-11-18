package com.cocos.challenge.orders;

import java.math.BigDecimal;
import java.util.UUID;

import com.cocos.challenge.CocosChallengeApplication;
import com.cocos.challenge.orders.application.port.in.PlaceOrderCommand;
import com.cocos.challenge.orders.application.port.in.PlaceOrderUseCase;
import com.cocos.challenge.orders.application.port.in.PlacedOrder;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderSizing;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.support.ApplicationIT;
import com.cocos.challenge.support.PostgresTestcontainersConfiguration;
import com.cocos.challenge.support.SeedDataRestorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key survives the application that stored it: an order is placed by a separate application context (another
 * "backend instance"), which is then closed; the test's own context, with nothing in memory about that request,
 * replays it from PostgreSQL instead of placing a second order.
 */
@ApplicationIT
class OrderIdempotencyRestartIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SeedDataRestorer seed;

    @AfterEach
    void restoreSeed() {
        seed.restoreOrders();
    }

    @Test
    void keyStoredByAPreviousInstanceIsReplayedAfterItStopped() {
        UUID key = UUID.randomUUID();
        PlacedOrder original;
        try (ConfigurableApplicationContext previousInstance = startAnotherInstance()) {
            original = previousInstance.getBean(PlaceOrderUseCase.class).place(new PlaceOrderCommand(1, 47,
                    OrderSide.BUY, OrderType.LIMIT, new OrderSizing.Quantity(10), new BigDecimal("900.50"), key));
        }
        assertThat(original.replayed()).isFalse();

        MvcTestResult retry = mvc.post().uri("/api/v1/users/1/orders").header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 10, "price": 900.50}""")
                .exchange();

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry).bodyJson().extractingPath("$.id").isEqualTo(original.order().id());
        assertThat(retry).bodyJson().extractingPath("$.status").isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(12);
    }

    private static ConfigurableApplicationContext startAnotherInstance() {
        return new SpringApplicationBuilder(CocosChallengeApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .properties(
                        "spring.datasource.url=" + PostgresTestcontainersConfiguration.jdbcUrl(),
                        "spring.datasource.username=" + PostgresTestcontainersConfiguration.username(),
                        "spring.datasource.password=" + PostgresTestcontainersConfiguration.password())
                .run();
    }
}
