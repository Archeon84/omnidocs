# Project Rules

## Project Overview

This is an Android notes application with an on-device RAG system.

The application is designed for general Android users and should prioritize:

1. Reliability
2. Responsiveness
3. Low memory usage
4. Privacy
5. Offline functionality
6. Maintainability

The application uses a local LLM for AI features and should not depend on a remote server for core note-taking functionality.

## Core Architecture

The project is organized around these major areas:

- UI
- Application/domain logic
- Local persistence
- RAG pipeline
- Local LLM inference
- Security
- Background processing

Keep these responsibilities separated.

Do not introduce a new architectural pattern when an existing project pattern can solve the problem.

## AI Architecture

The AI pipeline is conceptually:

Notes
→ preprocessing
→ chunking
→ embeddings
→ retrieval
→ optional reranking
→ context construction
→ local LLM
→ response

Changes to one stage should not silently change the assumptions of another stage.

Do not solve a retrieval problem by blindly increasing prompt size.

Prefer bounded context construction and efficient retrieval.

## Local AI

The application uses on-device inference.

AI-related changes must consider:

- RAM usage
- CPU/GPU/NPU usage
- latency
- battery consumption
- model loading time
- context size
- concurrency
- cancellation
- device compatibility

Do not assume desktop-class hardware.

The application must remain usable while AI features are processing.

## Performance

Performance is a primary product requirement.

Avoid:

- unnecessary allocations
- repeated file reads
- repeated database queries
- duplicate embedding work
- unnecessary model reloads
- blocking the main thread
- processing entire large documents when a bounded operation is sufficient

Prefer incremental, streaming, paged, cached, or background processing where appropriate.

## Offline-First

Core note functionality should work without network access.

Do not introduce network dependencies into core functionality unless explicitly required.

Cloud services should be treated as optional integrations rather than assumptions.

## Data Integrity

User notes and locally stored data must be treated as persistent user data.

Do not delete, reset, migrate, or replace stored data as a shortcut.

Database changes must preserve compatibility with existing installations.

## Security

Security-sensitive functionality must preserve the existing threat model and key-management design.

Do not weaken encryption, authentication, backup protection, or local data protections to simplify implementation.

Never hardcode secrets or credentials.

## Repository Conventions

Follow the existing repository structure, naming conventions, dependency versions, formatting, and build system.

Before adding a new dependency, check whether existing project dependencies already provide the required capability.

Avoid unrelated refactoring.

## Change Discipline

Make the smallest change that correctly solves the task.

Before changing architecture or shared infrastructure:

1. Inspect existing implementations.
2. Identify affected components.
3. Consider compatibility and side effects.
4. Make the smallest appropriate change.
5. Run relevant verification.

Do not rewrite working code without a clear reason.

## Verification

After significant changes:

- run relevant tests
- run lint or static analysis when applicable
- compile/build affected modules
- inspect the final diff
- verify that existing behavior remains intact

Never claim a change was verified when it was not.

## Important Rule

When a specialized rule conflicts with a generic implementation preference, preserve the project's established architecture and data/security requirements unless the task explicitly requires changing them.