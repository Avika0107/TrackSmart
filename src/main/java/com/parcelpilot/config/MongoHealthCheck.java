package com.parcelpilot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Graceful startup: a clear message when Mongo is unreachable (actuator health also shows DOWN). */
@Component
public class MongoHealthCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoHealthCheck.class);

    private final MongoTemplate mongo;

    public MongoHealthCheck(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Async so the 30s Mongo driver timeout never slows the boot.
        Thread.ofPlatform().name("mongo-health-check").daemon(true).start(() -> {
            try {
                mongo.executeCommand("{ping: 1}");
                log.info("MongoDB reachable");
            } catch (Exception e) {
                log.error("--------------------------------------------------------------------");
                log.error("MongoDB is NOT reachable. Start it with 'docker compose up mongo' or");
                log.error("set MONGODB_URI to your Atlas connection string. Dashboard/API calls");
                log.error("will fail until the database is up.");
                log.error("--------------------------------------------------------------------");
            }
        });
    }
}
