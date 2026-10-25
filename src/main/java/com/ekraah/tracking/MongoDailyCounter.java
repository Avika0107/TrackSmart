package com.ekraah.tracking;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Tracking-API calls per day, counted with an atomic $inc on one document per day
 * ("tracking_budget", _id = "2026-10-26"), so every instance draws from the same budget.
 * Old days expire through a TTL index on expireAt (MongoConfig).
 */
@Component
public class MongoDailyCounter implements TrackingBudget.DailyCounter {

    static final String COLLECTION = "tracking_budget";

    private final MongoTemplate mongo;

    public MongoDailyCounter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    @SuppressWarnings("unchecked")
    public long incrementAndGet(LocalDate day) {
        Map<String, Object> doc = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(day.toString())),
                new Update().inc("calls", 1)
                        .setOnInsert("expireAt", day.plusDays(7).atStartOfDay().toInstant(ZoneOffset.UTC)),
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                Map.class, COLLECTION);
        return ((Number) doc.get("calls")).longValue();
    }
}
