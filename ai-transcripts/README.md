# Recorded implementation sessions

These JSONL snapshots derive from eleven recorded Codex sessions in the Reviq workspace, covering planning, implementation and its participating agents, public review imports, and the bug audit and deployment. They preserve the chronology of visible user/assistant messages and tool-event occurrences. They are **privacy-filtered public exports, not complete or unedited raw transcripts**. No missing messages, tool results, or reasoning are reconstructed.

Publication applies a conservative boundary:

- Visible user/assistant text is retained with explicit credential, private-path, address, and unrelated-host substitutions where necessary.
- Runtime and agent context is replaced with a clearly marked omission entry.
- Every tool call retains its recorded timestamp, type, name, and call identifier when available. Its complete argument/input body is omitted.
- Every tool result retains its recorded timestamp, type, and call identifier. Its complete result body is omitted. Tool results can embed host inventories, unrelated repository details, private conversation context, tool catalogs, or privileged instruction documents, so selective text matching is insufficient.
- Protected instructions, private reasoning, internal bookkeeping, and non-text attachments are excluded. Internal message metadata is never exported.

`manifest.json` records the format, snapshot time, session purposes, event counts, omission/substitution counts, and SHA-256 hashes of the published files. Original Codex session logs remain unchanged in local session storage and are not included in this public repository. Consequently, the implementation plan's complete-raw-transcript submission gate is not claimed as satisfied by these files.

Regenerate the snapshots with:

```sh
python3 ops/export-transcripts.py --self-test
python3 ops/export-transcripts.py
```

The exporter reads credential values locally for exact-value redaction and validation without printing them. Its regression checks cover credentials, private host/path/email details, tool-result omission, runtime context, and exclusion of protected messages. The active session's snapshot ends when the exporter runs; it may therefore precede the final response, commit, pull request, or merge. The repository already existed and included the supplied implementation plan. Each export remains a separate recorded session; overlapping context is not presented as a single uninterrupted conversation.
