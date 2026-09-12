package com.offerpilot;

import com.offerpilot.config.OfferPilotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(OfferPilotProperties.class)
public class OfferPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfferPilotApplication.class, args);
    }
}
