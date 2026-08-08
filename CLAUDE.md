## Approach
- Read existing files before writing. Don't re-read unless changed.
- Thorough in reasoning, concise in output.
- Skip files over 100KB unless required.
- No sycophantic openers or closing fluff.
- No emojis or em-dashes.
- Do not guess APIs, versions, flags, commit SHAs, or package names. Verify by reading code or docs before asserting.

## Agent Model Selection
Default subagents to haiku. Upgrade only when task requires judgment:
- haiku: file reading, data gathering, counting, scanning, formatting
- sonnet: analysis, code review, writing, moderate reasoning
- opus: architecture decisions, novel debugging, cross-cutting synthesis

## Compact Instructions
When compacting this conversation, always preserve:
- Current task context and progress
- File paths being modified and their state
- Test results and error messages
- Active constraints and decisions made
- User preferences expressed in this session
