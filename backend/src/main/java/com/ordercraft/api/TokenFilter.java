package com.ordercraft.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public class TokenFilter extends OncePerRequestFilter {
  private final JdbcTemplate db; private final String secret;
  public TokenFilter(JdbcTemplate db, String secret) { this.db = db; this.secret = secret; }
  static String sign(String input, String secret) {
    try { Mac m=Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(input.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  static String token(long userId, String secret, long hours) {
    String payload=userId+":"+(Instant.now().getEpochSecond()+hours*3600)+":"+UUID.randomUUID();
    String body=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)); return body+"."+sign(body,secret);
  }
  @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
    String value=req.getHeader("Authorization");
    if(value!=null && value.startsWith("Bearer ")) {
      try {
        String[] pieces=value.substring(7).split("\\.");
        if(pieces.length!=2 || !java.security.MessageDigest.isEqual(sign(pieces[0],secret).getBytes(StandardCharsets.UTF_8),pieces[1].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
        String[] p=new String(Base64.getUrlDecoder().decode(pieces[0]),StandardCharsets.UTF_8).split(":"); long uid=Long.parseLong(p[0]);
        if(Long.parseLong(p[1])<Instant.now().getEpochSecond()) throw new IllegalArgumentException();
        Map<String,Object> user=db.queryForMap("SELECT id,tenant_id,email,full_name FROM user_account WHERE id=? AND enabled=TRUE",uid);
        List<UserContext.CompanyAccess> companies=db.query("SELECT c.id,c.name,c.code,a.role_name FROM user_company_assignment a JOIN company c ON c.id=a.company_id WHERE a.user_id=? AND c.tenant_id=? AND c.active=TRUE ORDER BY c.id",(rs,n)->new UserContext.CompanyAccess(rs.getLong("id"),rs.getString("name"),rs.getString("code"),rs.getString("role_name")),uid,user.get("tenant_id"));
        if(companies.isEmpty()) throw new IllegalArgumentException();
        long requested=req.getHeader("X-Company-Id")==null?companies.getFirst().id():Long.parseLong(req.getHeader("X-Company-Id"));
        var access=companies.stream().filter(c->c.id()==requested).findFirst().orElseThrow(IllegalArgumentException::new);
        UserContext ctx=new UserContext(((Number)user.get("id")).longValue(),((Number)user.get("tenant_id")).longValue(),access.id(),(String)user.get("email"),(String)user.get("full_name"),access.role(),companies);
        var auth=new UsernamePasswordAuthenticationToken(ctx,value.substring(7),List.of(new SimpleGrantedAuthority("ROLE_"+access.role()))); SecurityContextHolder.getContext().setAuthentication(auth);
      } catch(Exception e) { SecurityContextHolder.clearContext(); res.setStatus(401); res.setContentType("application/json"); res.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Invalid session or company context\"}"); return; }
    }
    chain.doFilter(req,res);
  }
}

