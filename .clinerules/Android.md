---
paths:
  - "app/**"
  - "**/*.kt"
  - "**/*.kts"
---

# Android Rules

## Architecture

- Follow the existing Android architecture and package structure.
- Prefer the architecture already established by the project rather than introducing a competing pattern.
- Keep UI, state, domain logic, data access, and infrastructure responsibilities separated.
- Do not move code between layers unless necessary for the task.

## Kotlin

- Follow existing Kotlin style and idioms.
- Prefer immutable state where practical.
- Avoid unnecessary nullable values and unsafe `!!`.
- Use coroutines and structured concurrency consistently with the existing project.
- Do not introduce blocking work on the main thread.

## Android Performance

- Avoid unnecessary recompositions, allocations, database queries, and background work.
- Keep expensive operations off the main thread.
- Be careful with memory usage because the application is intended to run on a wide range of Android devices.
- Avoid loading entire large notes or datasets into memory when streaming, paging, or bounded processing is sufficient.

## Lifecycle

- Respect Android lifecycle and configuration changes.
- Avoid leaking Activity, Fragment, View, or Context references.
- Use lifecycle-aware components and scopes.

## Dependencies

- Reuse existing Android/Jetpack dependencies before adding new ones.
- Do not add a library for functionality that can be implemented cleanly with the existing stack.

## Verification

- Run the relevant Gradle task, tests, lint, or build after significant changes.
- Check both compile-time and runtime implications of Android changes.