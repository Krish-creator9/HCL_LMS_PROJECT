package com.ordercraft.api;

import java.util.List;
public record UserContext(long userId, long tenantId, long companyId, String email, String name, String role, List<CompanyAccess> companies) {
  public record CompanyAccess(long id, String name, String code, String role) {}
}
