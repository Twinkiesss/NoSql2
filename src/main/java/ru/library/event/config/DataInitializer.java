package ru.library.event.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.library.event.model.Event;
import ru.library.event.model.UserAccount;
import ru.library.event.repository.UserAccountRepository;
import ru.library.event.service.EventService;

@Component
public class DataInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final EventService eventService;
    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminId;
    private final String adminPassword;

    public DataInitializer(EventService eventService,
                           UserAccountRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.security.admin-user:admin}") String adminId,
                           @Value("${app.security.admin-password:admin123}") String adminPassword) {
        this.eventService = eventService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminId = adminId;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(String... args) {
        UserAccount admin = userRepository.findById(adminId).orElseGet(() ->
                new UserAccount(adminId, passwordEncoder.encode(adminPassword)));
        admin.setRole("ADMIN");
        userRepository.save(admin);

        if (!eventService.getAllEvents().isEmpty()) return;
        eventService.createEvent(new Event("Война и мир", "Роман-эпопея Л.Н. Толстого", "Лев Толстой", "Классическая литература", 5));
        eventService.createEvent(new Event("Преступление и наказание", "Роман Ф.М. Достоевского", "Фёдор Достоевский", "Классическая литература", 3));
        eventService.createEvent(new Event("Мастер и Маргарита", "Роман М.А. Булгакова", "Михаил Булгаков", "Советская литература", 4));
        eventService.createEvent(new Event("1984", "Антиутопия Дж. Оруэлла", "Джордж Оруэлл", "Зарубежная литература", 2));
        eventService.createEvent(new Event("Мастерство программирования", "Практическое руководство", "Роберт Мартин", "Информатика", 6));
        log.info("Demo data initialized in PostgreSQL");
    }
}
