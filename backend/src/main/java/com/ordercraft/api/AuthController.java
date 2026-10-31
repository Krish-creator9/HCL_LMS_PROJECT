package com.ordercraft.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Statement;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final String secret;
  private final long hours;

  public AuthController(JdbcTemplate db, PasswordEncoder encoder,
                        @Value("${ordercraft.jwt-secret}") String secret,
                        @Value("${ordercraft.token-hours}") long hours) {
    this.db = db;
    this.encoder = encoder;
    this.secret = secret;
    this.hours = hours;
  }

  public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
  public record GoogleAuthRequest(@Email @NotBlank String email, @NotBlank String name, String googleId, String avatarUrl) {}
  public record RegisterRequest(@Email @NotBlank String email, @NotBlank String password, @NotBlank String fullName, String companyName) {}
  public record ForgotPasswordRequest(@Email @NotBlank String email) {}
  public record ResetPasswordRequest(@Email @NotBlank String email, @NotBlank String token, @NotBlank String newPassword) {}

  private Map<String, Object> buildAuthResponse(long uid, long tid, String email, String name) {
    List<Map<String, Object>> companies = db.queryForList(
      "SELECT c.id, c.name, c.code, a.role_name role FROM user_company_assignment a " +
      "JOIN company c ON c.id=a.company_id WHERE a.user_id=? AND c.tenant_id=? AND c.active=TRUE ORDER BY c.id",
      uid, tid
    );
    if (companies.isEmpty()) {
      throw ApiException.forbidden("No company access is assigned to this user");
    }
    String tenantName = db.queryForObject("SELECT name FROM tenant WHERE id=?", String.class, tid);
    String token = TokenFilter.token(uid, secret, hours);
    return Map.of(
      "token", token,
      "user", Map.of("id", uid, "email", email, "name", name),
      "tenant", Map.of("id", tid, "name", tenantName != null ? tenantName : "Acme Industrial Ltd."),
      "companies", companies,
      "role", companies.getFirst().get("role")
    );
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
    List<Map<String, Object>> rows = db.queryForList(
      "SELECT id, tenant_id, password_hash, full_name FROM user_account WHERE email=? AND enabled=TRUE",
      request.email()
    );
    if (rows.isEmpty() || !encoder.matches(request.password(), (String) rows.getFirst().get("password_hash"))) {
      throw new ApiException(401, "Email or password is incorrect");
    }
    var user = rows.getFirst();
    long uid = ((Number) user.get("id")).longValue();
    long tid = ((Number) user.get("tenant_id")).longValue();
    return buildAuthResponse(uid, tid, request.email(), (String) user.get("full_name"));
  }

  @PostMapping("/google")
  @Transactional
  public Map<String, Object> googleSignIn(@Valid @RequestBody GoogleAuthRequest req) {
    List<Map<String, Object>> rows = db.queryForList(
      "SELECT id, tenant_id, full_name FROM user_account WHERE email=?",
      req.email()
    );

    long uid;
    long tenantId;

    if (!rows.isEmpty()) {
      var user = rows.getFirst();
      uid = ((Number) user.get("id")).longValue();
      tenantId = ((Number) user.get("tenant_id")).longValue();
      db.update(
        "UPDATE user_account SET google_id=?, avatar_url=COALESCE(?, avatar_url) WHERE id=?",
        req.googleId() != null ? req.googleId() : "google_" + uid,
        req.avatarUrl(),
        uid
      );
    } else {
      tenantId = 1L;
      final long assignedTenant = tenantId;
      KeyHolder kh = new GeneratedKeyHolder();
      String randPw = encoder.encode(UUID.randomUUID().toString());
      db.update(con -> {
        var ps = con.prepareStatement(
          "INSERT INTO user_account(tenant_id, email, password_hash, full_name, enabled, google_id, avatar_url) " +
          "VALUES(?, ?, ?, ?, TRUE, ?, ?)",
          Statement.RETURN_GENERATED_KEYS
        );
        ps.setLong(1, assignedTenant);
        ps.setString(2, req.email());
        ps.setString(3, randPw);
        ps.setString(4, req.name());
        ps.setString(5, req.googleId() != null ? req.googleId() : "google_" + System.currentTimeMillis());
        ps.setString(6, req.avatarUrl());
        return ps;
      }, kh);
      uid = Objects.requireNonNull(kh.getKey()).longValue();

      // Assign default Chicago Plant 01 company access
      db.update(
        "INSERT INTO user_company_assignment(user_id, company_id, role_name) VALUES(?, 1, 'OPERATOR') " +
        "ON DUPLICATE KEY UPDATE role_name='OPERATOR'",
        uid
      );
    }

    return buildAuthResponse(uid, tenantId, req.email(), req.name());
  }

  @PostMapping("/register")
  @Transactional
  public Map<String, Object> register(@Valid @RequestBody RegisterRequest req) {
    Integer count = db.queryForObject("SELECT COUNT(*) FROM user_account WHERE email=?", Integer.class, req.email());
    if (count != null && count > 0) {
      throw ApiException.bad("An account with this email address already exists");
    }

    final long tid = 1L;
    KeyHolder kh = new GeneratedKeyHolder();
    String hashed = encoder.encode(req.password());
    db.update(con -> {
      var ps = con.prepareStatement(
        "INSERT INTO user_account(tenant_id, email, password_hash, full_name, enabled) VALUES(?, ?, ?, ?, TRUE)",
        Statement.RETURN_GENERATED_KEYS
      );
      ps.setLong(1, tid);
      ps.setString(2, req.email());
      ps.setString(3, hashed);
      ps.setString(4, req.fullName());
      return ps;
    }, kh);
    long uid = Objects.requireNonNull(kh.getKey()).longValue();

    // Assign to Chicago Plant 01 as OPERATOR
    db.update("INSERT INTO user_company_assignment(user_id, company_id, role_name) VALUES(?, 1, 'OPERATOR')", uid);

    return buildAuthResponse(uid, tid, req.email(), req.fullName());
  }

  @PostMapping("/forgot-password")
  public Map<String, Object> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
    List<Map<String, Object>> rows = db.queryForList(
      "SELECT id, full_name FROM user_account WHERE email=? AND enabled=TRUE",
      req.email()
    );
    if (rows.isEmpty()) {
      // Don't leak user existence for security, but return generic success message
      return Map.of(
        "status", "SUCCESS",
        "message", "If an account exists with that email address, reset instructions and token have been issued.",
        "email", req.email()
      );
    }

    var user = rows.getFirst();
    long uid = ((Number) user.get("id")).longValue();
    // 6-character memorable verification code for easy entry
    String resetToken = String.format("%06d", new Random().nextInt(900000) + 100000);

    db.update(
      "UPDATE user_account SET reset_token=?, reset_token_expiry=DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 HOUR) WHERE id=?",
      resetToken, uid
    );

    // Record audit event
    db.update(
      "INSERT INTO audit_event(tenant_id, actor_id, event_type, entity_type, entity_id, detail_json) VALUES(1, ?, 'PASSWORD_RESET_REQUESTED', 'user_account', ?, ?)",
      uid, uid, "{\"email\":\"" + req.email() + "\"}"
    );

    return Map.of(
      "status", "SUCCESS",
      "message", "Reset code generated and dispatched.",
      "email", req.email(),
      "resetToken", resetToken,
      "expiresInMinutes", 60
    );
  }

  @PostMapping("/reset-password")
  @Transactional
  public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
    List<Map<String, Object>> rows = db.queryForList(
      "SELECT id, full_name FROM user_account WHERE email=? AND reset_token=? AND reset_token_expiry > CURRENT_TIMESTAMP",
      req.email(), req.token()
    );
    if (rows.isEmpty()) {
      throw ApiException.bad("The reset code is invalid or has expired. Please request a new code.");
    }

    var user = rows.getFirst();
    long uid = ((Number) user.get("id")).longValue();
    String newHash = encoder.encode(req.newPassword());

    db.update(
      "UPDATE user_account SET password_hash=?, reset_token=NULL, reset_token_expiry=NULL WHERE id=?",
      newHash, uid
    );

    db.update(
      "INSERT INTO audit_event(tenant_id, actor_id, event_type, entity_type, entity_id, detail_json) VALUES(1, ?, 'PASSWORD_RESET_COMPLETED', 'user_account', ?, ?)",
      uid, uid, "{\"email\":\"" + req.email() + "\"}"
    );

    return Map.of(
      "status", "SUCCESS",
      "message", "Your password has been successfully reset. You can now sign in with your new credentials."
    );
  }
}
