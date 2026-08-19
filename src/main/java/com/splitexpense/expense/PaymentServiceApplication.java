package com.payflow.payment;

import com.payflow.payment.config.AuthProperties;
import com.payflow.payment.config.JwtProperties;
import com.payflow.payment.config.ServiceAccountProperties;
import com.payflow.payment.config.WalletProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * PayFlow payment-service: orchestrates idempotent transfers between wallets held by
 * wallet-service.
 *
 * <p>Validates JWTs issued by auth-service, the same way wallet-service does, and additionally
 * holds a service account to authenticate its own outbound calls onto wallet-service's
 * internal endpoints — see {@link ServiceAccountProperties}.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        JwtProperties.class,
        AuthProperties.class,
        WalletProperties.class,
        ServiceAccountProperties.class
})
public class PaymentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentServiceApplication.class, args);
	}

}
