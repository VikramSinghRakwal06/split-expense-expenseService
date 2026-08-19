package com.splitexpense.expense.client;

import com.splitexpense.expense.client.dto.ApplyBalancesPayload;
import com.splitexpense.expense.client.dto.ApplyBalancesResult;
import com.splitexpense.expense.client.dto.GroupBalancesView;
import com.splitexpense.expense.client.dto.GroupErrorBody;
import com.splitexpense.expense.client.dto.GroupView;
import com.splitexpense.expense.exception.GroupOperationException;
import com.splitexpense.expense.exception.GroupServiceUnavailableException;
import com.splitexpense.expense.exception.NoSuchGroupException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The only way this service touches a group's balances: the two internal calls onto
 * group-service that back every expense and settlement.
 *
 * <p>Every failure group-service or the network can produce is translated here into exactly
 * one of the three exceptions {@code application.yml}'s {@code resilience4j.*} configuration
 * already knows how to treat:
 *
 * <ul>
 *   <li>{@link NoSuchGroupException} — the caller cannot see this group. Never retried,
 *       never counted against the breaker.</li>
 *   <li>{@link GroupOperationException} — group-service reached a decision and reported it.
 *       Whatever it was, it is not this service's to second-guess by retrying.</li>
 *   <li>{@link GroupServiceUnavailableException} — group-service could not be reached, or
 *       answered in a way that means "unwell". Retried, and counted against the breaker.</li>
 * </ul>
 *
 * <p>{@code @CircuitBreaker} and {@code @Retry} only take effect on calls arriving through
 * this bean's Spring proxy, so {@link #apply} and {@link #getGroupForCaller} must never call
 * each other or themselves directly.
 */
@Component
public class GroupClient {

    private static final Logger log = LoggerFactory.getLogger(GroupClient.class);

    private final RestClient groupRestClient;
    private final AdminTokenProvider adminTokenProvider;

    public GroupClient(
            @Qualifier("groupRestClient") RestClient groupRestClient,
            AdminTokenProvider adminTokenProvider) {
        this.groupRestClient = groupRestClient;
        this.adminTokenProvider = adminTokenProvider;
    }

    /**
     * Resolves a group the caller belongs to, forwarding their own bearer token rather than
     * the service account's — this is a read the caller is entitled to make for themselves,
     * not an internal movement, so it must not be escalated to admin.
     *
     * @param groupId             group to read
     * @param authorizationHeader the caller's own {@code Authorization} header, verbatim
     * @return the group and its current membership
     * @throws NoSuchGroupException              the group does not exist, or the caller is
     *                                            not a member of it
     * @throws GroupServiceUnavailableException  group-service could not be reached or is
     *                                            unwell
     */
    @CircuitBreaker(name = "groupService", fallbackMethod = "groupUnavailable")
    @Retry(name = "groupService")
    public GroupView getGroupForCaller(UUID groupId, String authorizationHeader) {
        try {
            return groupRestClient.get()
                    .uri("/api/v1/groups/{groupId}", groupId)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(GroupView.class);

        } catch (HttpClientErrorException.NotFound ex) {
            throw new NoSuchGroupException(
                    "No such group, or you are not a member of it: " + groupId);

        } catch (HttpClientErrorException ex) {
            throw new GroupOperationException(ex.getStatusCode().value(), extractMessage(ex));

        } catch (HttpServerErrorException ex) {
            throw new GroupServiceUnavailableException(
                    "group-service responded with " + ex.getStatusCode().value(), ex);

        } catch (ResourceAccessException ex) {
            throw new GroupServiceUnavailableException("group-service was unreachable", ex);
        }
    }

    /**
     * Reads a group's current pairwise debts, forwarding the caller's own bearer token —
     * same reasoning as {@link #getGroupForCaller}: this is a read the caller is entitled to
     * make for themselves, not an internal movement.
     *
     * @param groupId             group to read
     * @param authorizationHeader the caller's own {@code Authorization} header, verbatim
     * @return the group's outstanding pair balances
     * @throws NoSuchGroupException             the group does not exist, or the caller is
     *                                           not a member of it
     * @throws GroupServiceUnavailableException group-service could not be reached or is
     *                                           unwell
     */
    @CircuitBreaker(name = "groupService", fallbackMethod = "balancesUnavailable")
    @Retry(name = "groupService")
    public GroupBalancesView getBalances(UUID groupId, String authorizationHeader) {
        try {
            return groupRestClient.get()
                    .uri("/api/v1/groups/{groupId}/balances", groupId)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(GroupBalancesView.class);

        } catch (HttpClientErrorException.NotFound ex) {
            throw new NoSuchGroupException(
                    "No such group, or you are not a member of it: " + groupId);

        } catch (HttpClientErrorException ex) {
            throw new GroupOperationException(ex.getStatusCode().value(), extractMessage(ex));

        } catch (HttpServerErrorException ex) {
            throw new GroupServiceUnavailableException(
                    "group-service responded with " + ex.getStatusCode().value(), ex);

        } catch (ResourceAccessException ex) {
            throw new GroupServiceUnavailableException("group-service was unreachable", ex);
        }
    }

    /**
     * Applies a set of debts to a group. INTERNAL: authenticates with this service's own
     * admin service token, never the caller's — see {@link AdminTokenProvider}.
     *
     * @param groupId group to apply to
     * @param payload the reference, reason and deltas
     * @return whether this call did the work, or found it already done, plus the resulting
     *         balances
     * @throws GroupOperationException          group-service refused the apply — an archived
     *                                          group, a participant who is not a member, or
     *                                          a malformed payload
     * @throws GroupServiceUnavailableException group-service could not be reached or is
     *                                          unwell
     */
    @CircuitBreaker(name = "groupService", fallbackMethod = "applyUnavailable")
    @Retry(name = "groupService")
    public ApplyBalancesResult apply(UUID groupId, ApplyBalancesPayload payload) {
        return attempt(groupId, payload, true);
    }

    /**
     * @param allowTokenRetry whether a 401 may be retried once after forcing a fresh service
     *                        token; false on the retry itself, so a second rejection cannot
     *                        loop
     */
    private ApplyBalancesResult attempt(
            UUID groupId, ApplyBalancesPayload payload, boolean allowTokenRetry) {

        try {
            return groupRestClient.post()
                    .uri("/api/v1/groups/{groupId}/balances:apply", groupId)
                    .header(HttpHeaders.AUTHORIZATION, adminTokenProvider.bearerToken())
                    .body(payload)
                    .retrieve()
                    .body(ApplyBalancesResult.class);

        } catch (HttpClientErrorException.Unauthorized ex) {
            if (!allowTokenRetry) {
                throw new GroupServiceUnavailableException(
                        "group-service rejected the service token twice in a row", ex);
            }
            log.warn("Service token rejected by group-service; refreshing and retrying once");
            adminTokenProvider.invalidate();
            return attempt(groupId, payload, false);

        } catch (HttpClientErrorException ex) {
            throw new GroupOperationException(ex.getStatusCode().value(), extractMessage(ex));

        } catch (HttpServerErrorException ex) {
            throw new GroupServiceUnavailableException(
                    "group-service responded with " + ex.getStatusCode().value(), ex);

        } catch (ResourceAccessException ex) {
            // Connect refused, connect timeout, or read timeout. See
            // GroupServiceUnavailableException's javadoc: unlike the transfer saga this
            // replaces, a read timeout here costs nothing extra — the apply is idempotent by
            // referenceId, so leaving the expense INITIATED for a later retry is always safe.
            throw new GroupServiceUnavailableException("group-service was unreachable", ex);
        }
    }

    @SuppressWarnings("unused")
    private GroupView groupUnavailable(
            UUID groupId, String authorizationHeader, Throwable throwable) {
        throw new GroupServiceUnavailableException("group-service is currently unavailable", throwable);
    }

    @SuppressWarnings("unused")
    private GroupBalancesView balancesUnavailable(
            UUID groupId, String authorizationHeader, Throwable throwable) {
        throw new GroupServiceUnavailableException("group-service is currently unavailable", throwable);
    }

    /**
     * Invoked by the resilience4j proxy in place of {@link #apply} once the breaker is open,
     * or after every retry attempt on a {@link GroupServiceUnavailableException} is exhausted.
     * {@link GroupOperationException} is configured as an ignored exception in
     * {@code application.yml}, so it never reaches this method.
     */
    @SuppressWarnings("unused")
    private ApplyBalancesResult applyUnavailable(
            UUID groupId, ApplyBalancesPayload payload, Throwable throwable) {
        throw new GroupServiceUnavailableException("group-service is currently unavailable", throwable);
    }

    private String extractMessage(HttpStatusCodeException ex) {
        try {
            GroupErrorBody body = ex.getResponseBodyAs(GroupErrorBody.class);
            if (body != null && body.message() != null && !body.message().isBlank()) {
                return body.message();
            }
        } catch (RuntimeException ignored) {
            // Response body was not the expected shape; fall through to the generic message.
        }
        return "group-service responded with " + ex.getStatusCode().value();
    }
}
