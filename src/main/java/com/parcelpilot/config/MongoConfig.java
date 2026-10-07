package com.parcelpilot.config;

import com.parcelpilot.model.AuditLog;
import com.parcelpilot.model.Order;
import com.parcelpilot.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Indexes are created programmatically so they exist on any fresh database.
 * Creation is resilient: a temporarily unreachable Mongo logs a warning and the
 * app still boots (spec: graceful degradation, clear message).
 *
 * The (userId, sourceMessageId) unique index is partial: manual orders have no
 * message id and must not collide on null.
 */
@Configuration
public class MongoConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoConfig.class);

    @Bean
    public SmartInitializingSingleton indexInitializer(MongoTemplate mongo) {
        return () -> Thread.ofPlatform().name("mongo-index-init").daemon(true).start(() -> ensureIndexes(mongo));
    }

    private static void ensureIndexes(MongoTemplate mongo) {
        try {
            mongo.indexOps(Order.class).ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("status", Sort.Direction.ASC));
            mongo.indexOps(Order.class).ensureIndex(new Index().on("userId", Sort.Direction.ASC)
                    .on("sourceMessageId", Sort.Direction.ASC)
                    .unique()
                    .partial(PartialIndexFilter.of(new Criteria("sourceMessageId").ne(null))));
            mongo.indexOps(Order.class).ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("trackingNumber", Sort.Direction.ASC));

            mongo.indexOps(User.class).ensureIndex(new Index().on("phone", Sort.Direction.ASC).unique());
            mongo.indexOps(User.class).ensureIndex(new Index().on("inboundAlias", Sort.Direction.ASC).unique());

            mongo.indexOps(AuditLog.class).ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("time", Sort.Direction.DESC));
            log.info("MongoDB indexes ensured");
        } catch (Exception e) {
            log.warn("Could not create MongoDB indexes (database unreachable?): {}", e.getMessage());
        }
    }
}
