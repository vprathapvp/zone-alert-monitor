package com.geoalert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class GeoAlertApplication {

    public static void main(String[] args) {
        // "migrate" runs schema migrations and the zone load, then exits. It is a separate
        // one-shot container so that schema changes are an explicit, ordered deploy step.
        if (args.length > 0 && "migrate".equals(args[0])) {
            System.exit(MigrateCommand.run());
        }
        SpringApplication.run(GeoAlertApplication.class, args);
    }
}
