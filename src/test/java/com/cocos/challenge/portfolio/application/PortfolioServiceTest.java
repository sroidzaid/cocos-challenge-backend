package com.cocos.challenge.portfolio.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cocos.challenge.instruments.application.port.in.InstrumentQuery;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.instruments.domain.InstrumentType;
import com.cocos.challenge.marketdata.application.port.in.MarketDataQuery;
import com.cocos.challenge.marketdata.domain.MarketQuote;
import com.cocos.challenge.orders.application.port.in.LedgerQuery;
import com.cocos.challenge.orders.domain.CashBalance;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.orders.domain.Reservations;
import com.cocos.challenge.portfolio.domain.Portfolio;
import com.cocos.challenge.shared.domain.NotFoundException;
import com.cocos.challenge.users.application.port.in.UserLookup;
import com.cocos.challenge.users.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock
    private UserLookup users;

    @Mock
    private LedgerQuery ledger;

    @Mock
    private InstrumentQuery instruments;

    @Mock
    private MarketDataQuery marketData;

    @InjectMocks
    private PortfolioService service;

    @Test
    void unknownUserIsNotFound() {
        when(users.findById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPortfolio(999))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo("USER_NOT_FOUND");
        verifyNoInteractions(ledger, instruments, marketData);
    }

    @Test
    void accountWithoutPositionsDoesNotQueryInstrumentsNorPrices() {
        givenUser(2, "5000.00", "0", List.of());

        Portfolio portfolio = service.getPortfolio(2);

        assertThat(portfolio.totalAccountValue()).isEqualByComparingTo("5000.00");
        assertThat(portfolio.positions()).isEmpty();
        verifyNoInteractions(instruments, marketData);
    }

    @Test
    void combinesLedgerInstrumentsAndLatestPrices() {
        givenUser(1, "1000.00", "200.00", List.of(
                new Order(1, 1, 47, OrderSide.BUY, OrderType.MARKET, OrderStatus.FILLED, 2, new BigDecimal("100"),
                        LocalDateTime.of(2023, 7, 12, 10, 0))));
        when(instruments.findAllByIds(List.of(47)))
                .thenReturn(List.of(new Instrument(47, "PAMP", "Pampa Holding S.A.", InstrumentType.ACCIONES)));
        when(marketData.findLatestByInstrumentIds(List.of(47))).thenReturn(Map.of(47,
                new MarketQuote(47, LocalDate.of(2023, 7, 14), new BigDecimal("110.00"), new BigDecimal("100.00"))));

        Portfolio portfolio = service.getPortfolio(1);

        assertThat(portfolio.availableCash()).isEqualByComparingTo("800.00");
        assertThat(portfolio.totalAccountValue()).isEqualByComparingTo("1220.00");
        assertThat(portfolio.positions()).singleElement().satisfies(p -> {
            assertThat(p.ticker()).isEqualTo("PAMP");
            assertThat(p.totalReturnPercent()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void positionOnAMissingInstrumentFailsExplicitly() {
        givenUser(1, "0", "0", List.of(
                new Order(1, 1, 4242, OrderSide.BUY, OrderType.MARKET, OrderStatus.FILLED, 2, new BigDecimal("100"),
                        LocalDateTime.of(2023, 7, 12, 10, 0))));
        when(instruments.findAllByIds(List.of(4242))).thenReturn(List.of());
        when(marketData.findLatestByInstrumentIds(List.of(4242))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.getPortfolio(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("4242");
    }

    private void givenUser(int userId, String cash, String reservedCash, List<Order> filled) {
        when(users.findById(anyInt())).thenReturn(Optional.of(new User(userId, "u@test.com", "1000" + userId)));
        when(ledger.cashBalance(userId)).thenReturn(new CashBalance(new BigDecimal(cash)));
        when(ledger.reservations(userId)).thenReturn(new Reservations(new BigDecimal(reservedCash), Map.of()));
        when(ledger.filledOrders(userId)).thenReturn(filled);
    }
}
