---
paths:
  - "**/database/**"
  - "**/db/**"
  - "**/*Dao*"
  - "**/*Entity*"
  - "**/*Migration*"
---

# Database Rules

## Schema Safety

- Treat existing user data as valuable and persistent.
- Do not delete or rename database fields, tables, or indexes without considering migration and backwards compatibility.
- Never reset or recreate the database as a shortcut for resolving schema problems.
- Preserve existing data during migrations whenever possible.

## Migrations

- Every schema change must have an appropriate migration when required by the database framework.
- Inspect the existing migration history before creating a new migration.
- Do not modify old migrations that may already have been released.
- Prefer adding a new migration over rewriting migration history.

## Queries

- Reuse existing DAOs, repositories, and query patterns.
- Avoid unnecessary database queries.
- Avoid loading large datasets into memory when a bounded query is sufficient.
- Prefer indexed queries for frequently accessed fields when appropriate.

## Transactions

- Use transactions when multiple related database operations must succeed or fail together.
- Keep transactions focused and avoid performing expensive external or AI operations inside database transactions.

## RAG Data

- Preserve relationships between notes, chunks, embeddings, and metadata.
- Do not modify embedding-related schema without considering existing indexed embeddings.
- When changing the embedding model or vector representation, determine how existing embeddings will be migrated or invalidated.

## Verification

- Test migrations against representative existing database states.
- Verify that existing user data remains readable after schema changes.
- Run relevant database and integration tests after schema modifications.