package com.coop.racemgr.controllers;

import com.coop.racemgr.audit.AuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditController {
    @Autowired
    private AuditLog auditLog;

    @GetMapping("/api/v1/admin/audit")
    public ResponseEntity<Object> audit(@RequestParam(defaultValue = "100") Integer limit) {
        try {
            return ResponseHandler.generateResponse("Recent admin activity", HttpStatus.OK, auditLog.recent(limit));
        } catch (Exception e) {
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }
}
