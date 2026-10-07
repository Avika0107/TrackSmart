package com.parcelpilot.repository;

import com.parcelpilot.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByPhone(String phone);
    Optional<User> findByInboundAlias(String inboundAlias);
}
