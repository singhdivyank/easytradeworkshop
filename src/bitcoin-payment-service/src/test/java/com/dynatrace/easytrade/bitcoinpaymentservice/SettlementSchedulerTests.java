package com.dynatrace.easytrade.bitcoinpaymentservice;

import com.dynatrace.easytrade.bitcoinpaymentservice.models.PaymentStatusType;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
public class SettlementSchedulerTests {
    @Mock
    DatabaseHelper dbHelper;
    @Mock
    Connection conn;

    @Test
    @SneakyThrows
    void advancesConfirmingToConfirmedAndPendingToConfirming() {
        Mockito.when(dbHelper.getConnection()).thenReturn(conn);
        Mockito.when(dbHelper.getPaymentIdsInStatus(conn, PaymentStatusType.CONFIRMING))
                .thenReturn(List.of("id-confirming-1"));
        Mockito.when(dbHelper.getPaymentIdsInStatus(conn, PaymentStatusType.PENDING))
                .thenReturn(List.of("id-pending-1", "id-pending-2"));

        SettlementScheduler scheduler = new SettlementScheduler(dbHelper);
        scheduler.run();

        // CONFIRMING -> CONFIRMED
        Mockito.verify(dbHelper).insertStatus(eq(conn), eq("id-confirming-1"),
                eq(PaymentStatusType.CONFIRMED), any());
        // PENDING -> CONFIRMING
        Mockito.verify(dbHelper).insertStatus(eq(conn), eq("id-pending-1"),
                eq(PaymentStatusType.CONFIRMING), any());
        Mockito.verify(dbHelper).insertStatus(eq(conn), eq("id-pending-2"),
                eq(PaymentStatusType.CONFIRMING), any());
    }

    @Test
    @SneakyThrows
    void noWorkWhenNoPaymentsPending() {
        Mockito.when(dbHelper.getConnection()).thenReturn(conn);
        Mockito.when(dbHelper.getPaymentIdsInStatus(any(), any())).thenReturn(List.of());

        SettlementScheduler scheduler = new SettlementScheduler(dbHelper);
        scheduler.run();

        Mockito.verify(dbHelper, Mockito.never()).insertStatus(any(), any(), any(), any());
    }
}
