package com.cocos.challenge.orders.application;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import com.cocos.challenge.orders.application.port.out.OrderRepositoryPort;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.shared.domain.ConflictException;
import com.cocos.challenge.shared.domain.NotFoundException;
import com.cocos.challenge.users.application.port.in.AccountLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelOrderServiceTest {

    @Mock
    private AccountLock accountLock;

    @Mock
    private OrderRepositoryPort orders;

    @InjectMocks
    private CancelOrderService service;

    @Test
    void cancelsANewOrderAfterLockingTheAccount() {
        when(accountLock.lock(1)).thenReturn(true);
        when(orders.findByIdAndUserId(5, 1)).thenReturn(Optional.of(order(OrderStatus.NEW)));
        when(orders.cancelIfNew(5, 1)).thenReturn(true);

        Order cancelled = service.cancel(1, 5);

        InOrder order = inOrder(accountLock, orders);
        order.verify(accountLock).lock(1);
        order.verify(orders).findByIdAndUserId(5, 1);
        order.verify(orders).cancelIfNew(5, 1);
        assertThat(cancelled).isEqualTo(order(OrderStatus.CANCELLED));
    }

    @Test
    void finalStatesCannotBeCancelled() {
        for (OrderStatus status : new OrderStatus[] {OrderStatus.FILLED, OrderStatus.REJECTED, OrderStatus.CANCELLED}) {
            when(accountLock.lock(1)).thenReturn(true);
            when(orders.findByIdAndUserId(5, 1)).thenReturn(Optional.of(order(status)));

            assertThatThrownBy(() -> service.cancel(1, 5))
                    .isInstanceOf(ConflictException.class)
                    .extracting("code").isEqualTo("ORDER_NOT_CANCELLABLE");
        }
        verify(orders, never()).cancelIfNew(anyInt(), anyInt());
    }

    @Test
    void conditionalUpdateThatMatchesNothingIsAConflict() {
        when(accountLock.lock(1)).thenReturn(true);
        when(orders.findByIdAndUserId(5, 1)).thenReturn(Optional.of(order(OrderStatus.NEW)));
        when(orders.cancelIfNew(5, 1)).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(1, 5)).isInstanceOf(ConflictException.class);
    }

    @Test
    void orderOfAnotherUserOrMissingIsNotFound() {
        when(accountLock.lock(2)).thenReturn(true);
        when(orders.findByIdAndUserId(5, 2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(2, 5))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void unknownUserIsNotFound() {
        when(accountLock.lock(999)).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(999, 5))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo("USER_NOT_FOUND");
        verifyNoInteractions(orders);
    }

    private static Order order(OrderStatus status) {
        return new Order(5, 1, 45, OrderSide.BUY, OrderType.LIMIT, status, 50, new BigDecimal("710.00"),
                LocalDateTime.of(2023, 7, 12, 15, 14, 20));
    }
}
