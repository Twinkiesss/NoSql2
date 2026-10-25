package ru.library.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.library.event.model.UserAccount;
import ru.library.event.model.UserRole;

import java.util.List;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {
    List<UserAccount> findByRoleOrderByDisplayNameAsc(UserRole role);

    long countByRole(UserRole role);
}
