BEGIN;

INSERT INTO users(name, email, phone, kyc_status)
SELECT 'Demo User ' || user_number,
       'demo' || user_number || '@example.test',
       '+1555' || LPAD(user_number::text, 7, '0'),
       'VERIFIED'
  FROM generate_series(1, 1000) AS s(user_number);

INSERT INTO accounts(owner_user_id, account_type, account_name)
SELECT u.id, 'USER_WALLET', wallet.name
  FROM users u
 CROSS JOIN (VALUES ('Main wallet'), ('Savings wallet')) AS wallet(name)
 WHERE u.email LIKE 'demo%@example.test';

INSERT INTO transfers(id, initiated_by_user_id, transfer_type, status, amount_paise, description)
SELECT transfer_number,
       ((transfer_number - 1) % 1000) + 1,
       'TOP_UP',
       'COMPLETED',
       1000 + (transfer_number % 1000),
       'Seed top-up'
  FROM generate_series(1, 100000) AS s(transfer_number);

INSERT INTO transfer_postings(transfer_id, account_id, direction, amount_paise, sequence_number)
SELECT t.id, system_account.id, 'DEBIT', t.amount_paise, 1
  FROM transfers t
 CROSS JOIN LATERAL (SELECT id FROM accounts WHERE account_type = 'CASH_IN' LIMIT 1) system_account
 WHERE t.description = 'Seed top-up'
UNION ALL
SELECT t.id, wallet.id, 'CREDIT', t.amount_paise, 2
  FROM transfers t
  JOIN accounts wallet
    ON wallet.owner_user_id = t.initiated_by_user_id
   AND wallet.account_type = 'USER_WALLET'
   AND wallet.account_name = CASE WHEN t.id % 2 = 0 THEN 'Main wallet' ELSE 'Savings wallet' END
 WHERE t.description = 'Seed top-up';

INSERT INTO ledger_entries(transfer_id, account_id, direction, amount_paise)
SELECT t.id, system_account.id, 'DEBIT', t.amount_paise
  FROM transfers t
 CROSS JOIN LATERAL (SELECT id FROM accounts WHERE account_type = 'CASH_IN' LIMIT 1) system_account
 WHERE t.description = 'Seed top-up'
UNION ALL
SELECT t.id, wallet.id, 'CREDIT', t.amount_paise
  FROM transfers t
  JOIN accounts wallet
    ON wallet.owner_user_id = t.initiated_by_user_id
   AND wallet.account_type = 'USER_WALLET'
   AND wallet.account_name = CASE WHEN t.id % 2 = 0 THEN 'Main wallet' ELSE 'Savings wallet' END
 WHERE t.description = 'Seed top-up';

SELECT setval(pg_get_serial_sequence('transfers', 'id'), GREATEST((SELECT MAX(id) FROM transfers), 1), TRUE);

COMMIT;