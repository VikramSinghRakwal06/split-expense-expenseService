package com.splitexpense.expense;

import com.splitexpense.expense.config.AuthProperties;
import com.splitexpense.expense.config.GroupProperties;
import com.splitexpense.expense.config.JwtProperties;
import com.splitexpense.expense.config.ServiceAccountProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * SplitExpense expense-service: records expenses and settlements, and applies the balance
 * deltas they produce to group-service's debt graph.
 *
 * <p>This service holds no balances of its own — see {@code Expense}'s and
 * {@code Settlement}'s javadoc. Validates JWTs issued by auth-service, the same way
 * group-service does, and additionally holds a service account to authenticate its own
 * outbound calls onto group-service's internal {@code :apply} endpoint — see
 * {@link ServiceAccountProperties}.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        JwtProperties.class,
        AuthProperties.class,
        GroupProperties.class,
        ServiceAccountProperties.class
})
public class ExpenseServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExpenseServiceApplication.class, args);
	}

}
