# Five-Minute Demo Runbook

Start the application and database with `docker compose up --build`. Use a separate terminal for the requests. Replace the IDs with values returned by earlier calls.

1. Create a user with `POST /api/users`; save the returned `accessToken`. Send a UUID `Idempotency-Key` header on each POST. Create a wallet with `POST /api/users/{userId}/wallets`, using `Authorization: Bearer <accessToken>`.
2. Top up 100000 paise using `POST /api/users/{userId}/transfers`. Repeat the exact request with the same key and show that the response body and transfer ID are unchanged.
3. Send 5000 paise to another wallet. Fetch both balances and the sender's statement to show the matching ledger rows.
4. Submit a top-up of 600000 paise. The default rule holds it; show the unchanged wallet balance and the flag in the database or admin view.
5. Release it with `POST /api/admin/transfers/{transferId}/release`, `X-Admin-Token`, a new idempotency key, and a review reason. Configure `ADMIN_USER_ID` to an existing user for the audit actor.
6. Run `POST /api/admin/reconciliation/run` with the admin token and a new key. Show the persisted run report.

The demo admin token defaults to `dev-admin-token`; set `ADMIN_API_TOKEN` for a non-local environment. Record the requested five-minute video manually after running through these steps; credentials and tokens should not appear in the recording.