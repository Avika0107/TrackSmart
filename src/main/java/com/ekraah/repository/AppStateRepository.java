package com.ekraah.repository;

import com.ekraah.model.AppState;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AppStateRepository extends MongoRepository<AppState, String> {
}
