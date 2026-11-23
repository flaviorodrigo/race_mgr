package com.coop.racemgr.repositories;

import com.coop.racemgr.model.AdminUser;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AdminUserRepository extends MongoRepository<AdminUser, String> {
    AdminUser findByUsername(String username);

    List<AdminUser> findAllByOrderByUsernameAsc();
}
