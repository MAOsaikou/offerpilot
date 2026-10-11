package com.offerpilot.demo;

import com.offerpilot.auth.application.DemoAccountBootstrap;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class DemoJourneyBootstrap implements ApplicationRunner {
    private final DemoAccountBootstrap accounts;
    private final DemoResetService resets;
    private final com.offerpilot.config.OfferPilotProperties.Demo config;

    public DemoJourneyBootstrap(DemoAccountBootstrap accounts, DemoResetService resets, com.offerpilot.config.OfferPilotProperties properties) {
        this.config = properties.demo();
        this.accounts = accounts;
        this.resets = resets;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!accounts.enabled() || config.sandboxEnabled()) return;
        String id = accounts.ensure();
        if (id != null) resets.seedIfEmpty(id);
    }

    /** Uses the server timezone; cron defaults to 04:00 daily. */
    @Scheduled(cron = "${offerpilot.demo.reset-cron:0 0 4 * * *}")
    public void scheduledReset() {
        if (!config.sandboxEnabled() && accounts.enabled() && accounts.ensure() != null) resets.reset();
    }
}
