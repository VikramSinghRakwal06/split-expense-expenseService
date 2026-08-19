package com.payflow.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payflow.payment.dto.response.TransactionResponse;
import com.payflow.payment.entity.TransactionStatus;
import com.payflow.payment.exception.IdempotencyKeyConflictException;
import com.payflow.payment.exception.NoWalletException;
import com.payflow.payment.exception.SameWalletTransferException;
import com.payflow.payment.exception.TransferFailedException;
import com.payflow.payment.exception.TransferInProgressException;
import com.payflow.payment.exception.TransferReversedException;
import com.payflow.payment.exception.TransferUnresolvedException;
import com.payflow.payment.exception.WalletOperationException;
import com.payflow.payment.exception.WalletServiceUnavailableException;
import com.payflow.payment.security.AuthenticatedUser;
import com.payflow.payment.security.JwtTokenValidator;
import com.payflow.payment.service.PaymentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test: request mapping, header requirements, payload validation, and the
 * exception-to-status-code contract from {@code GlobalExceptionHandler}.
 *
 * <p>{@link PaymentService} is mocked and the security filter chain is switched off (the
 * caller is placed on the context directly, mirroring what {@code JwtAuthenticationFilter}
 * would have done), so a failure here is a controller failure, not a token or service one.
 */
@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {

    private static final String TRANSFER = "/api/v1/payments/transfer";
    private static final String ME = "/api/v1/payments/me";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PaymentService paymentService;

    // The slice instantiates Filter beans even with the chain disabled, so
    // JwtAuthenticationFilter's collaborator must be supplied.
    @MockitoBean private JwtTokenValidator jwtTokenValidator;

    private final UUID callerId = UUID.randomUUID();
    private final UUID receiverWalletId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        AuthenticatedUser caller = new AuthenticatedUser(callerId, "ada@payflow.io", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private TransactionResponse completedTransaction() {
        return new TransactionResponse(
                transactionId, UUID.randomUUID(), receiverWalletId, new BigDecimal("50.0000"),
                "INR", TransactionStatus.COMPLETED, null, "lunch",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"));
    }

    private String transferBody(String receiverWalletId, String amount, String description) {
        return """
                {"receiverWalletId": "%s", "amount": %s, "description": %s}"""
                .formatted(receiverWalletId, amount, description == null ? "null" : "\"" + description + "\"");
    }

    @Nested
    @DisplayName("POST /transfer — headers and forwarding")
    class TransferForwarding {

        @Test
        @DisplayName("a completed transfer is answered with 200 and the service's response")
        void successfulTransferReturnsOk() throws Exception {
            when(paymentService.transfer(any(), any(), any(), any()))
                    .thenReturn(completedTransaction());

            mockMvc.perform(post(TRANSFER)
                            .header(IDEMPOTENCY_KEY, "key-1")
                            .header("Authorization", "Bearer caller-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(receiverWalletId.toString(), "50.0000", "lunch")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(transactionId.toString()))
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }

        @Test
        @DisplayName("forwards the caller from the security context, the raw Idempotency-Key and Authorization header, and the body verbatim")
        void forwardsExactlyWhatTheRequestCarried() throws Exception {
            when(paymentService.transfer(any(), any(), any(), any()))
                    .thenReturn(completedTransaction());

            mockMvc.perform(post(TRANSFER)
                            .header(IDEMPOTENCY_KEY, "key-42")
                            .header("Authorization", "Bearer caller-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(receiverWalletId.toString(), "50.0000", "lunch")))
                    .andExpect(status().isOk());

            ArgumentCaptor<AuthenticatedUser> caller = ArgumentCaptor.forClass(AuthenticatedUser.class);
            verify(paymentService).transfer(
                    caller.capture(), eq("key-42"), any(), eq("Bearer caller-token"));
            org.assertj.core.api.Assertions.assertThat(caller.getValue().id()).isEqualTo(callerId);
        }

        @Test
        @DisplayName("a missing Idempotency-Key header is a 400, not a 500")
        void missingIdempotencyKeyIsBadRequest() throws Exception {
            mockMvc.perform(post(TRANSFER)
                            .header("Authorization", "Bearer caller-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(receiverWalletId.toString(), "50.0000", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString(IDEMPOTENCY_KEY)));

            verify(paymentService, never()).transfer(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a missing Authorization header is a 400, not a 500")
        void missingAuthorizationIsBadRequest() throws Exception {
            mockMvc.perform(post(TRANSFER)
                            .header(IDEMPOTENCY_KEY, "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(receiverWalletId.toString(), "50.0000", null)))
                    .andExpect(status().isBadRequest());

            verify(paymentService, never()).transfer(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("POST /transfer — request validation")
    class TransferValidation {

        @Test
        @DisplayName("a missing receiver wallet id is a field-level 400")
        void missingReceiverIsRejected() throws Exception {
            mockMvc.perform(post(TRANSFER)
                            .header(IDEMPOTENCY_KEY, "key-1")
                            .header("Authorization", "Bearer caller-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 10.00}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors.receiverWalletId").exists());

            verify(paymentService, never()).transfer(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a zero amount is a field-level 400")
        void zeroAmountIsRejected() throws Exception {
            mockMvc.perform(post(TRANSFER)
                            .header(IDEMPOTENCY_KEY, "key-1")
                            .header("Authorization", "Bearer caller-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(receiverWalletId.toString(), "0", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors.amount").exists());
        }

        @Test
        @DisplayName("an amount with more than 4 decimal places is a field-level 400")
        void excessivePrecisionIsRejected() throws Exception {
            mockMvc.perform(post(TRANSFER)
                            .header(IDEMPOTENCY_KEY, "key-1")
                            .header("Authorization", "Bearer caller-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(receiverWalletId.toString(), "1.23456", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors.amount").exists());
        }

        @Test
        @DisplayName("malformed JSON is a 400, not a 500")
        void malformedBodyIsBadRequest() throws Exception {
            mockMvc.perform(post(TRANSFER)
                            .header(IDEMPOTENCY_KEY, "key-1")
                            .header("Authorization", "Bearer caller-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /transfer — exception-to-status mapping")
    class TransferOutcomes {

        private void stubFailure(RuntimeException ex) {
            when(paymentService.transfer(any(), any(), any(), any())).thenThrow(ex);
        }

        private org.springframework.test.web.servlet.ResultActions attempt() throws Exception {
            return mockMvc.perform(post(TRANSFER)
                    .header(IDEMPOTENCY_KEY, "key-1")
                    .header("Authorization", "Bearer caller-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(transferBody(receiverWalletId.toString(), "50.0000", null)));
        }

        @Test
        @DisplayName("SameWalletTransferException is a 400")
        void sameWalletIsBadRequest() throws Exception {
            stubFailure(new SameWalletTransferException("Cannot transfer to your own wallet"));
            attempt().andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("NoWalletException is a 404")
        void noWalletIsNotFound() throws Exception {
            stubFailure(new NoWalletException("No wallet exists for this account."));
            attempt().andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("IdempotencyKeyConflictException is a 422")
        void idempotencyConflictIsUnprocessable() throws Exception {
            stubFailure(new IdempotencyKeyConflictException("key reused with a different request"));
            attempt().andExpect(status().isUnprocessableEntity());
        }

        @Test
        @DisplayName("TransferFailedException is a 422")
        void transferFailedIsUnprocessable() throws Exception {
            stubFailure(new TransferFailedException("Wallet has insufficient funds"));
            attempt().andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value("Wallet has insufficient funds"));
        }

        @Test
        @DisplayName("TransferInProgressException is a 409")
        void inProgressIsConflict() throws Exception {
            stubFailure(new TransferInProgressException("Retry shortly."));
            attempt().andExpect(status().isConflict());
        }

        @Test
        @DisplayName("TransferReversedException is a 409, funds already returned")
        void reversedIsConflict() throws Exception {
            stubFailure(new TransferReversedException("Wallet is CLOSED"));
            attempt().andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("your funds were returned")));
        }

        @Test
        @DisplayName("TransferUnresolvedException is a 500 that says do not retry")
        void unresolvedIsInternalServerError() throws Exception {
            stubFailure(new TransferUnresolvedException(transactionId));
            attempt().andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Do not retry")));
        }

        @Test
        @DisplayName("WalletOperationException passes wallet-service's own status code through")
        void walletOperationPassesStatusThrough() throws Exception {
            stubFailure(new WalletOperationException(409, "Wallet is FROZEN"));
            attempt().andExpect(status().isConflict());
        }

        @Test
        @DisplayName("WalletServiceUnavailableException is a 503")
        void walletUnavailableIsServiceUnavailable() throws Exception {
            stubFailure(new WalletServiceUnavailableException("wallet-service was unreachable"));
            attempt().andExpect(status().isServiceUnavailable());
        }
    }

    @Nested
    @DisplayName("GET /me")
    class History {

        @Test
        @DisplayName("defaults to 20 entries sorted by createdAt descending, and forwards the bearer token")
        void appliesDefaultPagingAndForwardsToken() throws Exception {
            when(paymentService.getTransactionsForCaller(any(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get(ME).header("Authorization", "Bearer caller-token"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(paymentService).getTransactionsForCaller(eq("Bearer caller-token"), pageable.capture());
            org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
            org.assertj.core.api.Assertions.assertThat(
                            pageable.getValue().getSort().getOrderFor("createdAt"))
                    .isNotNull()
                    .extracting(Sort.Order::getDirection)
                    .isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("a missing Authorization header is a 400")
        void missingAuthorizationIsBadRequest() throws Exception {
            mockMvc.perform(get(ME)).andExpect(status().isBadRequest());

            verify(paymentService, never()).getTransactionsForCaller(any(), any());
        }

        @Test
        @DisplayName("returns the page of transactions the service produced")
        void returnsServicePage() throws Exception {
            Page<TransactionResponse> page = new PageImpl<>(List.of(completedTransaction()));
            when(paymentService.getTransactionsForCaller(any(), any())).thenReturn(page);

            mockMvc.perform(get(ME).header("Authorization", "Bearer caller-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(transactionId.toString()))
                    .andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
        }

        @Test
        @DisplayName("a caller's own wallet id is never accepted from the request")
        void noWalletIdParameterExists() throws Exception {
            when(paymentService.getTransactionsForCaller(any(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            UUID someoneElse = UUID.randomUUID();
            mockMvc.perform(get(ME)
                            .header("Authorization", "Bearer caller-token")
                            .param("walletId", someoneElse.toString()))
                    .andExpect(status().isOk());

            // The controller has no parameter to bind such a value to; this only proves the
            // request is not rejected outright and the service call is unaffected.
            verify(paymentService).getTransactionsForCaller(eq("Bearer caller-token"), any());
        }
    }
}
