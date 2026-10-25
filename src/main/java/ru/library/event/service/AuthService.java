package ru.library.event.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.library.event.exception.InvalidCurrentPasswordException;
import ru.library.event.exception.UserAlreadyExistsException;
import ru.library.event.model.AdminRegisterRequest;
import ru.library.event.model.AuthRequest;
import ru.library.event.model.AuthResponse;
import ru.library.event.model.ChangePasswordRequest;
import ru.library.event.model.RegisterRequest;
import ru.library.event.model.UserAccount;
import ru.library.event.model.UserRole;
import ru.library.event.repository.UserAccountRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountRepository userAccountRepository;
    private final UserSettingsService userSettingsService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final String adminRegistrationCode;

    public AuthService(UserAccountRepository userAccountRepository,
                       UserSettingsService userSettingsService,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       @Value("${library.admin-registration-code:}") String adminRegistrationCode) {
        this.userAccountRepository = userAccountRepository;
        this.userSettingsService = userSettingsService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.adminRegistrationCode = adminRegistrationCode;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        return createUser(request.getUserId(), request.getDisplayName(), request.getPassword(), UserRole.USER);
    }

    @Transactional
    public AuthResponse registerAdmin(AdminRegisterRequest request) {
        if (adminRegistrationCode == null || adminRegistrationCode.isBlank()) {
            throw new AccessDeniedException(
                    "Admin registration is disabled: ADMIN_REGISTRATION_CODE is not configured");
        }
        if (request.getAdminCode() == null
                || !MessageDigest.isEqual(
                        adminRegistrationCode.getBytes(StandardCharsets.UTF_8),
                        request.getAdminCode().getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException("Invalid admin registration code");
        }
        return createUser(request.getUserId(), request.getDisplayName(), request.getPassword(), UserRole.ADMIN);
    }

    private AuthResponse createUser(String rawUserId, String displayName, String password, UserRole role) {
        String userId = normalizeUserId(rawUserId);
        if (userAccountRepository.existsById(userId)) {
            throw new UserAlreadyExistsException(userId);
        }

        UserAccount account = new UserAccount(
                userId, passwordEncoder.encode(password), displayName == null ? null : displayName.trim());
        account.setRole(role);
        userAccountRepository.save(account);
        userSettingsService.getOrCreateDefault(userId);
        log.info("Account registered: userId={}, role={}", userId, role);
        return toResponse(account);
    }

    public AuthResponse login(AuthRequest request) {
        String userId = normalizeUserId(request.getUserId());
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(userId, request.getPassword())
        );
        return toResponse(loadAccount(userId));
    }

    public AuthResponse currentUser(String userId) {
        return toResponse(loadAccount(userId));
    }

    @Transactional
    public void changePassword(String userId, ChangePasswordRequest request) {
        UserAccount user = loadAccount(userId);
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new InvalidCurrentPasswordException();
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userAccountRepository.save(user);
        log.info("Password changed for user {}", userId);
    }

    @Transactional
    public void deleteAccount(String userId) {
        userSettingsService.deleteSettings(userId);
        userAccountRepository.deleteById(userId);
        log.info("Account deleted: userId={}", userId);
    }

    private UserAccount loadAccount(String userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
    }

    private static AuthResponse toResponse(UserAccount account) {
        return new AuthResponse(account.getId(), account.getDisplayName(), account.getRole().name());
    }

    private static String normalizeUserId(String userId) {
        return userId.trim().toLowerCase(Locale.ROOT);
    }
}
