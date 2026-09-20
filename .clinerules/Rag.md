---
paths:
  - "rag/**"
  - "src/rag/**"
  - "**/*embedding*"
  - "**/*retrieval*"
---

# RAG Rules

- Keep ingestion, chunking, embedding, retrieval, reranking, and generation logically separated.
- Do not inject an entire document into the prompt when retrieval can provide sufficient context.
- Prefer bounded context sizes.
- Preserve metadata needed to trace retrieved chunks back to their source notes.
- Do not change embedding models without considering existing indexed data.
- Evaluate retrieval quality separately from LLM generation quality.
- Avoid duplicate retrieval results.
- Preserve source attribution where applicable.