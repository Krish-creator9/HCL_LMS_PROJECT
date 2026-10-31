# OrderCraft release and recovery runbook

## Release sequence

1. Confirm the target database backup can be read and restored to a separate database.
2. Set the database connection values, a strong `ORDERCRAFT_JWT_SECRET`, and `ORDERCRAFT_DEMO_SEED=false` in the deployment environment. Do not use the local demo credentials or local values in `compose.yaml` for a shared environment.
3. Apply the Flyway migrations with the release build before routing users to the new application version. Review migration output and verify the API health endpoint.
4. Verify one user from each configured role can access only assigned companies. Check finance, payroll review/approval/posting, and integration-admin access with separate accounts.
5. Configure each company's base currency, tax profile, fiscal year, chart-of-account posting rules, and dated exchange rates. Select production provider credentials only after the corresponding adapter is configured and reviewed.
6. Run a controlled order through production, shipment, invoice, partial payment, and reconciliation in the target environment before general rollout.

## MySQL backup

Take a consistent logical backup before a release and on the schedule defined by the database owner. Store backups outside the application host with encryption and retention appropriate to the environment.

```sh
mysqldump --single-transaction --routines --triggers \
  --host="$DB_HOST" --user="$DB_USER" --password \
  "$DB_NAME" > "ordercraft-$(date +%Y%m%d-%H%M%S).sql"
```

The password prompt avoids placing the database password in the command text or shell history. Protect the resulting SQL file as production data.

## Restore

Restore to a new or explicitly selected database first. Check that the backup belongs to the expected environment and date, restore it, start the API against that database, and verify Flyway history and a small set of company-scoped records. Switch application traffic only after the restore is confirmed.

```sh
mysql --host="$DB_HOST" --user="$DB_USER" --password "$DB_NAME" \
  < ordercraft-backup.sql
```

For local Docker development, `docker compose down` stops containers while preserving the database volume. Do not add `-v` unless intentionally deleting local database data.

## Provider and jurisdiction boundaries

The bundled adapters are sandbox implementations. They do not contact payment processors, tax/e-invoice authorities, banks, carriers, email services, ERPs, CRMs, or machines. Payroll withholding is a configured percentage, not a jurisdictional tax engine. Replace or configure adapters and verify legal and operational requirements before production use. Native mobile targets are not selected; the current shop-floor client is responsive web UI.
