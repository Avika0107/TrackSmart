package com.ekraah.repository;

import com.ekraah.model.Order;
import com.ekraah.model.OrderStatus;
import com.ekraah.model.TrackingMode;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends MongoRepository<Order, String> {

    List<Order> findByUserId(String userId, Sort sort);

    Optional<Order> findByIdAndUserId(String id, String userId);

    Optional<Order> findByUserIdAndSourceMessageId(String userId, String sourceMessageId);

    /** Matches when messageId is one element of the order's messageIds array. */
    boolean existsByUserIdAndMessageIds(String userId, String messageId);

    Optional<Order> findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(String userId, String trackingNumber);

    long countByUserId(String userId);

    List<Order> findByStatusInAndTrackingMode(List<OrderStatus> statuses, TrackingMode trackingMode);

    List<Order> findByStatusIn(List<OrderStatus> statuses);

    List<Order> findByStatusInAndEstimatedDeliveryBefore(List<OrderStatus> statuses, Instant before);

    Optional<Order> findFirstByUserIdAndTrackingModeOrderByLastUpdatedDesc(String userId, TrackingMode trackingMode);

    List<Order> findByStatusAndDeliveredAtBefore(OrderStatus status, Instant cutoff);

    Optional<Order> findFirstByUserIdAndRetailerOrderIdOrderByLastUpdatedDesc(String userId, String retailerOrderId);
}
