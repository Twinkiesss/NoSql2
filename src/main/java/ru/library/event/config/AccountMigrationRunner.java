package ru.library.event.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.library.event.model.UserAccount;
import ru.library.event.repository.UserAccountRepository;

import java.util.List;

/**
 * Приводит данные, созданные до появления ролей и имён, к актуальной схеме.
 * Существующие пользователи становятся читателями, их именем становится идентификатор.
 */
@Component
@Order(0)
public class AccountMigrationRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountMigrationRunner.class);

    private final UserAccountRepository userAccountRepository;
    private final JdbcTemplate jdbcTemplate;

    public AccountMigrationRunner(UserAccountRepository userAccountRepository, JdbcTemplate jdbcTemplate) {
        this.userAccountRepository = userAccountRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(String... args) {
        List<UserAccount> accounts = userAccountRepository.findAll();
        for (UserAccount account : accounts) {
            account.setRole(account.getRole());
            account.setDisplayName(account.getDisplayName());
        }
        userAccountRepository.saveAll(accounts);

        try {
            jdbcTemplate.execute("alter table orders alter column manager_id drop not null");
            jdbcTemplate.update("update orders set manager_id = null where manager_id = user_id");
        } catch (Exception e) {
            log.warn("Could not adjust orders.manager_id: {}", e.getMessage());
        }

        log.info("Account migration completed: {} account(s) checked", accounts.size());
    }
}
