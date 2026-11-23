package com.coop.racemgr.repositories;

import com.coop.racemgr.model.AdminCredential;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminCredentialRepository extends MongoRepository<AdminCredential, String> {
}
