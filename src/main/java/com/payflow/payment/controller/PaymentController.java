package com.payflow.payment.controller;

import com.payflow.payment.dto.request.TransferRequest;
import com.payflow.payment.dto.response.ErrorResponse;
import com.payflow.payment.dto.response.TransactionResponse;
import com.payflow.payment.security.AuthenticatedUser;
import com.payflow.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for transfers.
 *
 * <p>Holds no business logic: each method resolves the caller, delegates to
 * {@link PaymentService}, and returns a DTO. Neither endpoint accepts a wallet id for the
 * caller's own side of the transfer — it is always resolved from the verified JWT via
 * wallet-service, so there is no parameter through which one user could move or read another
 * user's money.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Idempotent transfers between wallets")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Transfers money from the caller's wallet to another.
     *
     * @param caller         the verified caller
     * @param idempotencyKey client-generated key identifying this specific attempt; a retry
     *                       must resend the same key with the same body
     * @param authorization  the caller's own bearer token, forwarded to resolve their wallet
     * @param request        receiver, amount and optional description
     * @return 200 with the completed transfer
     */
    @PostMapping("/transfer")
    @Operation(summary = "Transfer money from the caller's wallet to another wallet",
            description = "Requires an Idempotency-Key header. Retrying the same key with the "
                    + "same body is always safe and returns the original outcome.")
    @ApiResponse(responseCode = "200", description = "Transfer completed")
    @ApiResponse(responseCode = "400", description = "Validation failed, or the receiver is "
            + "the caller's own wallet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "The caller has no wallet yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "A request with this key is already in "
            + "progress, or the transfer failed and was safely reversed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "The transfer was refused, or the "
            + "Idempotency-Key was reused with a different request",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "wallet-service is unavailable",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Parameter(description = "Client-generated key identifying this specific attempt")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody TransferRequest request) {

        TransactionResponse response =
                paymentService.transfer(caller, idempotencyKey, request, authorization);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * Returns one page of the caller's transfer history, as sender or receiver, newest first.
     *
     * @param authorization the caller's own bearer token, forwarded to resolve their wallet
     * @param pageable      paging and sort, defaulted to 20 per page by {@code createdAt} desc
     * @return 200 with a page of transactions
     */
    @GetMapping("/me")
    @Operation(summary = "Page through the authenticated user's transfer history")
    @ApiResponse(responseCode = "200", description = "A page of transactions")
    @ApiResponse(responseCode = "404", description = "The caller has no wallet yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Page<TransactionResponse>> myTransactions(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return ResponseEntity.ok(paymentService.getTransactionsForCaller(authorization, pageable));
    }
}
