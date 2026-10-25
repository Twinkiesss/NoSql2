package ru.library.event.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.library.event.model.UserStatisticsResponse;
import ru.library.event.service.UserStatisticsService;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserStatisticsController {
    private final UserStatisticsService statisticsService;

    public UserStatisticsController(UserStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/me/statistics")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserStatisticsResponse> getMyStatistics(Authentication authentication) {
        return ResponseEntity.ok(statisticsService.getStatistics(authentication.getName()));
    }
}
