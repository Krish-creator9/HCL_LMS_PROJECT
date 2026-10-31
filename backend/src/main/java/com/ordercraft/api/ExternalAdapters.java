package com.ordercraft.api;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

/** Provider ports used by the application. The included adapter is an in-process sandbox, never a live provider. */
public final class ExternalAdapters {
  private ExternalAdapters() {}
  public record Result(String status,String externalReference,Map<String,Object> details) {}
  public interface PaymentGateway { Result record(String idempotencyKey,Map<String,Object> request); }
  public interface TaxInvoiceNetwork { Result submit(String idempotencyKey,Map<String,Object> invoice); }
  public interface BankFeed { Result importStatement(String idempotencyKey,Map<String,Object> statement); }
  public interface Carrier { Result createLabel(String idempotencyKey,Map<String,Object> shipment); }
  public interface EmailDelivery { Result deliver(String idempotencyKey,Map<String,Object> message); }
  public interface ErpCrm { Result synchronize(String idempotencyKey,Map<String,Object> payload); }
  public interface MachineEvent { Result accept(String idempotencyKey,Map<String,Object> event); }

  @Component
  public static class Sandbox implements PaymentGateway,TaxInvoiceNetwork,BankFeed,Carrier,EmailDelivery,ErpCrm,MachineEvent {
    private Result result(String prefix,String key,Map<String,Object> input) {
      String ref=prefix+"-"+UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().substring(0,12).toUpperCase();
      return new Result("SANDBOX_ACCEPTED",ref,Map.of("mode","MOCK","message","No external provider was contacted","inputKeys",input.keySet()));
    }
    @Override public Result record(String k,Map<String,Object> r){return result("PAY",k,r);}
    @Override public Result submit(String k,Map<String,Object> r){return result("EINV",k,r);}
    @Override public Result importStatement(String k,Map<String,Object> r){return result("BANK",k,r);}
    @Override public Result createLabel(String k,Map<String,Object> r){return result("CARR",k,r);}
    @Override public Result deliver(String k,Map<String,Object> r){return result("MAIL",k,r);}
    @Override public Result synchronize(String k,Map<String,Object> r){return result("ERP",k,r);}
    @Override public Result accept(String k,Map<String,Object> r){return result("IOT",k,r);}
  }
}
