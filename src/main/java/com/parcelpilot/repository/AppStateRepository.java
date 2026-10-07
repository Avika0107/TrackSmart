package com.parcelpilot.repository;

import com.parcelpilot.model.AppState;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AppStateRepository extends MongoRepository<AppState, String> {
}
