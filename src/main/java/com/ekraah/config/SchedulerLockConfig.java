package com.ekraah.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.mongo.MongoLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * With more than one instance running, every @Scheduled job would run on each of them: duplicate
 * IMAP polls, double tracking-API spend, repeated alerts. @SchedulerLock jobs take a lock in the
 * "shedLock" collection first, so each run happens on one instance only.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(MongoTemplate mongo) {
        return new MongoLockProvider(mongo.getDb());
    }
}
