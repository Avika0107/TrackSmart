package com.ekraah.config;

import com.ekraah.model.Alert;
import com.ekraah.model.AuditLog;
import com.ekraah.model.Session;
import com.ekraah.model.Order;
import com.ekraah.model.User;
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
import org.springframework.data.mongodb.core.schema.JsonSchemaObject;

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
        // Each index is created on its own: one failure (e.g. a duplicate in old data) must not
        // silently skip the rest, as the user-uniqueness indexes are what enforce one account per phone.
        ensure(mongo, Order.class, "orders(userId,status)",
                new Index().on("userId", Sort.Direction.ASC).on("status", Sort.Direction.ASC));
        // Partial filter uses $type: Mongo rejects $ne/$not in partial indexes ("Expression not
        // supported"), which used to abort index creation entirely.
        ensure(mongo, Order.class, "orders(userId,sourceMessageId) unique",
                new Index().on("userId", Sort.Direction.ASC).on("sourceMessageId", Sort.Direction.ASC)
                        .unique()
                        .partial(PartialIndexFilter.of(new Criteria("sourceMessageId").type(JsonSchemaObject.Type.STRING))));
        ensure(mongo, Order.class, "orders(userId,messageIds)",
                new Index().on("userId", Sort.Direction.ASC).on("messageIds", Sort.Direction.ASC));
        ensure(mongo, Order.class, "orders(userId,trackingNumber)",
                new Index().on("userId", Sort.Direction.ASC).on("trackingNumber", Sort.Direction.ASC));
        ensure(mongo, Order.class, "orders(userId,retailerOrderId)",
                new Index().on("userId", Sort.Direction.ASC).on("retailerOrderId", Sort.Direction.ASC));

        ensure(mongo, User.class, "users(phone) unique", new Index().on("phone", Sort.Direction.ASC).unique());
        ensure(mongo, User.class, "users(inboundAlias) unique", new Index().on("inboundAlias", Sort.Direction.ASC).unique());

        ensure(mongo, AuditLog.class, "audit(userId,time)",
                new Index().on("userId", Sort.Direction.ASC).on("time", Sort.Direction.DESC));

        ensure(mongo, Alert.class, "alerts(userId,createdAt)",
                new Index().on("userId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        ensure(mongo, Alert.class, "alerts(userId,trackingNumber,kind,createdAt)",   // AlertService dedupe
                new Index().on("userId", Sort.Direction.ASC).on("trackingNumber", Sort.Direction.ASC)
                        .on("kind", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        ensure(mongo, Session.class, "sessions(tokenHash) unique",
                new Index().on("tokenHash", Sort.Direction.ASC).unique());
        ensure(mongo, Session.class, "sessions(previousTokenHash)",   // refresh-token reuse detection
                new Index().on("previousTokenHash", Sort.Direction.ASC));
        ensure(mongo, Session.class, "sessions(userId)", new Index().on("userId", Sort.Direction.ASC));
        try {   // TTL: per-day tracking-budget counters (MongoDailyCounter) delete themselves after a week
            mongo.indexOps("tracking_budget").ensureIndex(new Index().on("expireAt", Sort.Direction.ASC).expire(java.time.Duration.ZERO));
        } catch (Exception e) {
            log.warn("Could not create MongoDB index tracking_budget(expireAt) TTL: {}", e.getMessage());
        }
        try {   // TTL: MongoDB deletes OTP documents by itself once purgeAt has passed
            mongo.indexOps("otps").ensureIndex(new Index().on("purgeAt", Sort.Direction.ASC).expire(java.time.Duration.ZERO));
        } catch (Exception e) {
            log.warn("Could not create MongoDB index otps(purgeAt) TTL: {}", e.getMessage());
        }
        log.info("MongoDB index check finished");
    }

    private static void ensure(MongoTemplate mongo, Class<?> entity, String name, Index index) {
        try {
            mongo.indexOps(entity).ensureIndex(index);
        } catch (Exception e) {
            log.warn("Could not create MongoDB index {} (database unreachable or conflicting data?): {}", name, e.getMessage());
        }
    }
}
