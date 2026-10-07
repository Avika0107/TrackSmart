package com.parcelpilot.repository;

import com.parcelpilot.model.Order;
import com.parcelpilot.model.OrderStatus;
import com.parcelpilot.model.TrackingMode;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends MongoRepository<Order, String> {

    List<Order> findByUserId(String userId, Sort sort);

    Optional<Order> findByIdAndUserId(String id, String userId);

    Optional<Order> findByUserIdAndSourceMessageId(String userId, String sourceMessageId);

    Optional<Order> findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(String userId, String trackingNumber);

    long countByUserId(String userId);

    List<Order> findByStatusInAndTrackingMode(List<OrderStatus> statuses, TrackingMode trackingMode);

    List<Order> findByStatusAndDeliveredAtBefore(OrderStatus status, Instant cutoff);
}
