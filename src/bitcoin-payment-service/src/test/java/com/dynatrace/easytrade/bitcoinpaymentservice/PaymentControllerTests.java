package com.dynatrace.easytrade.bitcoinpaymentservice;

import com.dynatrace.easytrade.bitcoinpaymentservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.bitcoinpaymentservice.models.BitcoinPaymentResponse;
import com.dynatrace.easytrade.bitcoinpaymentservice.models.BitcoinPaymentStatus;
import com.dynatrace.easytrade.bitcoinpaymentservice.models.PaymentStatusType;
import com.dynatrace.easytrade.bitcoinpaymentservice.models.StandardResponse;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
public class PaymentControllerTests {
    @Mock
    DatabaseHelper dbHelper;
    @Mock
    OpenFeatureAPI openFeatureAPI;
    @Mock
    Client client;

    private final String PAYMENT_ID = "a67a075f-f1b3-4fd4-bfbd-e4986cd11956";

    private void featureEnabled(boolean enabled) {
        Mockito.when(openFeatureAPI.getClient()).thenReturn(client);
        Mockito.when(client.getBooleanValue(PaymentController.FEATURE_FLAG_KEY, false)).thenReturn(enabled);
    }

    private BitcoinPaymentRequest validRequest() {
        return new BitcoinPaymentRequest(13, new BigDecimal("100.00"), "USD", "bc1qexamplewalletaddress");
    }

    @Test
    @SneakyThrows
    void acceptedWhenFeatureEnabledAndRequestValid() {
        featureEnabled(true);
        Mockito.when(dbHelper.insertNewPayment(any())).thenReturn(PAYMENT_ID);

        PaymentController controller = new PaymentController(dbHelper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.createPayment(validRequest());
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.ACCEPTED.value(), body.statusCode());
        assertEquals(PaymentController.PAYMENT_ACCEPTED, body.message());
        BitcoinPaymentResponse result = (BitcoinPaymentResponse) body.results();
        assertEquals(PAYMENT_ID, result.paymentId());
        assertEquals(PaymentStatusType.PENDING.getType(), result.status());
    }

    @Test
    @SneakyThrows
    void serviceUnavailableWhenFeatureDisabled() {
        featureEnabled(false);

        PaymentController controller = new PaymentController(dbHelper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.createPayment(validRequest());
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), body.statusCode());
        assertEquals(PaymentController.FEATURE_DISABLED, body.message());
        // No DB write must happen when the feature is off.
        Mockito.verify(dbHelper, Mockito.never()).insertNewPayment(any());
    }

    @Test
    @SneakyThrows
    void badRequestWhenAmountNotPositive() {
        featureEnabled(true);
        BitcoinPaymentRequest bad = new BitcoinPaymentRequest(13, BigDecimal.ZERO, "USD", "bc1qexample");

        PaymentController controller = new PaymentController(dbHelper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.createPayment(bad);
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.BAD_REQUEST.value(), body.statusCode());
        Mockito.verify(dbHelper, Mockito.never()).insertNewPayment(any());
    }

    @Test
    @SneakyThrows
    void badRequestWhenWalletMissing() {
        featureEnabled(true);
        BitcoinPaymentRequest bad = new BitcoinPaymentRequest(13, new BigDecimal("5.00"), "USD", "  ");

        PaymentController controller = new PaymentController(dbHelper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.createPayment(bad);
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.BAD_REQUEST.value(), body.statusCode());
    }

    @Test
    @SneakyThrows
    void statusReturnsLatestWhenPresent() {
        BitcoinPaymentStatus status = new BitcoinPaymentStatus(
                PAYMENT_ID, 13, PaymentStatusType.CONFIRMING.getType(), OffsetDateTime.now(), "broadcast");
        Mockito.when(dbHelper.getLatestStatus(PAYMENT_ID)).thenReturn(Optional.of(status));

        PaymentController controller = new PaymentController(dbHelper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.getStatus(PAYMENT_ID);
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.OK.value(), body.statusCode());
        assertEquals(status, body.results());
    }

    @Test
    @SneakyThrows
    void statusReturnsNotFoundWhenAbsent() {
        Mockito.when(dbHelper.getLatestStatus(PAYMENT_ID)).thenReturn(Optional.empty());

        PaymentController controller = new PaymentController(dbHelper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.getStatus(PAYMENT_ID);
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.NOT_FOUND.value(), body.statusCode());
        assertEquals(PaymentController.PAYMENT_NOT_FOUND, body.message());
    }

    @Test
    void healthReturnsOk() {
        PaymentController controller = new PaymentController(dbHelper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.health();
        var body = response.getBody();

        assertNotNull(body);
        assertEquals(HttpStatus.OK.value(), body.statusCode());
    }
}
