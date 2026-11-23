package com.coop.racemgr.repositories;

import com.coop.racemgr.model.SessionPreset;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SessionPresetRepository extends MongoRepository<SessionPreset, String> {
    List<SessionPreset> findAllByOrderByNameAsc();

    SessionPreset findByName(String name);
}
