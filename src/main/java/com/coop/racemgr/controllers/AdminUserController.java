package com.coop.racemgr.controllers;

import com.coop.racemgr.audit.AuditLog;
import com.coop.racemgr.model.AdminRole;
import com.coop.racemgr.model.AdminUser;
import com.coop.racemgr.repositories.AdminUserRepository;
import com.coop.racemgr.service.JwtUserDetailsService;
import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The owner adds, changes and removes the other admins and stewards. Everyone can ask who they are. */
@RestController
public class AdminUserController {
    private static final Logger logger = LogManager.getLogger(AdminUserController.class);
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.-]{3,32}$");

    @Autowired
    private AdminUserRepository userRepository;
    @Autowired
    private JwtUserDetailsService userDetailsService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuditLog auditLog;

    public record UserView(String username, String role, Instant created) {}

    @Data
    public static class UserRequest {
        private String username;
        private String password;
        private String role;
    }

    @GetMapping("/api/v1/admin/me")
    public ResponseEntity<Object> me(Authentication authentication) {
        var user = (JwtUserDetailsService.RmUser) authentication.getPrincipal();
        return ResponseHandler.generateResponse("You", HttpStatus.OK, new UserView(user.getUsername(), user.getRole().name(), null));
    }

    @GetMapping("/api/v1/admin/users")
    public ResponseEntity<Object> list(Authentication authentication) {
        if (!isOwner(authentication)) {
            return forbidden();
        }

        try {
            List<UserView> users = new ArrayList<>();
            users.add(new UserView(userDetailsService.ownerName(), AdminRole.OWNER.name(), null));
            userRepository.findAllByOrderByUsernameAsc().forEach(u -> users.add(new UserView(u.getUsername(), u.getRole(), u.getCreated())));
            return ResponseHandler.generateResponse("Admins", HttpStatus.OK, users);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @PostMapping("/api/v1/admin/users")
    public ResponseEntity<Object> create(@RequestBody UserRequest request, Authentication authentication) {
        if (!isOwner(authentication)) {
            return forbidden();
        }

        String name = request.getUsername() == null ? null : request.getUsername().trim();
        AdminRole role = assignable(request.getRole());
        if (name == null || !USERNAME.matcher(name).matches()) {
            return ResponseHandler.generateResponse("A user name is 3 to 32 letters, digits, dots, dashes or underscores", HttpStatus.BAD_REQUEST, null);
        }
        if (role == null) {
            return ResponseHandler.generateResponse("The role must be ADMIN or STEWARD", HttpStatus.BAD_REQUEST, null);
        }
        String passwordProblem = passwordProblem(request.getPassword());
        if (passwordProblem != null) {
            return ResponseHandler.generateResponse(passwordProblem, HttpStatus.BAD_REQUEST, null);
        }
        if (userDetailsService.ownerName().equalsIgnoreCase(name)) {
            return ResponseHandler.generateResponse("That name is taken", HttpStatus.CONFLICT, null);
        }

        try {
            String stored = name.toLowerCase(Locale.ROOT);
            if (userRepository.findByUsername(stored) != null) {
                return ResponseHandler.generateResponse("That name is taken", HttpStatus.CONFLICT, null);
            }
            if (userRepository.count() >= AdminUser.MAX_USERS) {
                return ResponseHandler.generateResponse("At most " + AdminUser.MAX_USERS + " extra admins: remove one first", HttpStatus.BAD_REQUEST, null);
            }

            var user = new AdminUser();
            user.setUsername(stored);
            user.setRole(role.name());
            user.setCreated(Instant.now());
            JwtUserDetailsService.setPassword(user, passwordEncoder.encode(request.getPassword()));
            userRepository.save(user);
            auditLog.record(authentication.getName(), "admin added", stored + " as " + role.name());
            return ResponseHandler.generateResponse("Added " + stored, HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // Changes the role, resets the password, or both. A new password signs that person out everywhere.
    @PutMapping("/api/v1/admin/users/{username}")
    public ResponseEntity<Object> update(@PathVariable String username, @RequestBody UserRequest request, Authentication authentication) {
        if (!isOwner(authentication)) {
            return forbidden();
        }
        if (!USERNAME.matcher(username).matches()) {
            return ResponseHandler.generateResponse("User not found", HttpStatus.NOT_FOUND, null);
        }

        AdminRole role = request.getRole() == null ? null : assignable(request.getRole());
        if (request.getRole() != null && role == null) {
            return ResponseHandler.generateResponse("The role must be ADMIN or STEWARD", HttpStatus.BAD_REQUEST, null);
        }
        if (request.getPassword() != null) {
            String problem = passwordProblem(request.getPassword());
            if (problem != null) {
                return ResponseHandler.generateResponse(problem, HttpStatus.BAD_REQUEST, null);
            }
        }
        if (role == null && request.getPassword() == null) {
            return ResponseHandler.generateResponse("Give a new role or a new password", HttpStatus.BAD_REQUEST, null);
        }

        try {
            AdminUser user = userRepository.findByUsername(username.toLowerCase(Locale.ROOT));
            if (user == null) {
                return ResponseHandler.generateResponse("User not found", HttpStatus.NOT_FOUND, null);
            }

            StringBuilder what = new StringBuilder();
            if (role != null) {
                user.setRole(role.name());
                what.append("role ").append(role.name());
            }
            if (request.getPassword() != null) {
                JwtUserDetailsService.setPassword(user, passwordEncoder.encode(request.getPassword()));
                what.append(what.length() > 0 ? ", " : "").append("password reset");
            }
            userRepository.save(user);
            auditLog.record(authentication.getName(), "admin changed", user.getUsername() + ": " + what);
            return ResponseHandler.generateResponse("Saved", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @DeleteMapping("/api/v1/admin/users/{username}")
    public ResponseEntity<Object> delete(@PathVariable String username, Authentication authentication) {
        if (!isOwner(authentication)) {
            return forbidden();
        }
        if (!USERNAME.matcher(username).matches()) {
            return ResponseHandler.generateResponse("User not found", HttpStatus.NOT_FOUND, null);
        }

        try {
            AdminUser user = userRepository.findByUsername(username.toLowerCase(Locale.ROOT));
            if (user == null) {
                return ResponseHandler.generateResponse("User not found", HttpStatus.NOT_FOUND, null);
            }

            userRepository.delete(user);
            auditLog.record(authentication.getName(), "admin removed", user.getUsername());
            return ResponseHandler.generateResponse("Removed " + user.getUsername(), HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // The URL rules already limit this to the owner; checked again here because these endpoints hand out access
    private static boolean isOwner(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"));
    }

    private static ResponseEntity<Object> forbidden() {
        return ResponseHandler.generateResponse("Only the owner can do this", HttpStatus.FORBIDDEN, null);
    }

    private static AdminRole assignable(String text) {
        AdminRole role = AdminRole.parse(text);
        return role != null && role.isAssignable() ? role : null;
    }

    private static String passwordProblem(String password) {
        return password == null || password.length() < AccountController.MIN_PASSWORD_LENGTH || password.length() > AccountController.MAX_PASSWORD_LENGTH
                ? "The password must be " + AccountController.MIN_PASSWORD_LENGTH + " to " + AccountController.MAX_PASSWORD_LENGTH + " characters"
                : null;
    }
}
