package ru.library.event.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.library.event.model.GlobalStatisticsResponse;
import ru.library.event.service.GlobalStatisticsService;

@RestController
@RequestMapping("/api/statistics")
@CrossOrigin(origins = "*")
public class GlobalStatisticsController {
    private final GlobalStatisticsService statisticsService;

    public GlobalStatisticsController(GlobalStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/global")
    @PreAuthorize("hasRole('ADMIN')")
    public GlobalStatisticsResponse getGlobalStatistics() {
        return statisticsService.getStatistics();
    }
}
