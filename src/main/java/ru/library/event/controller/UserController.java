package ru.library.event.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.library.event.model.UserRole;
import ru.library.event.model.UserSummaryResponse;
import ru.library.event.repository.UserAccountRepository;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {
    private final UserAccountRepository userAccountRepository;

    public UserController(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserSummaryResponse>> listReaders() {
        return ResponseEntity.ok(userAccountRepository.findByRoleOrderByDisplayNameAsc(UserRole.USER).stream()
                .map(user -> new UserSummaryResponse(user.getId(), user.getDisplayName()))
                .toList());
    }
}
