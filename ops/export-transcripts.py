#!/usr/bin/env python3
"""Export a privacy-filtered public record of genuine Codex session events.

Original logs are never modified. Only visible user/assistant text is retained;
all tool arguments/results are omitted because they can embed credentials,
private host inventories, protected instructions, or unrelated conversation data.
This is deliberately NOT a complete or unedited raw transcript.
"""
from __future__ import annotations

import argparse
import collections
import datetime
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
SESSIONS = {
    '01a17fa8-abe3-7081-9300-9ea7dea879cb': 'implementation coordinator',
    '01a17fa9-4a51-7f21-aeb4-a1c92e7e4ea6': 'reference design and frontend',
    '01a17fa9-f243-7da2-a86a-2acb277f5d79': 'Kotlin backend and tests',
    '01a17faa-891a-7a40-b759-95174b48e3c3': 'acceptance tests and publication review',
}
PUBLIC_DOMAINS = {
    'reviq.xyz', 'www.reviq.xyz', 'revenue-iq-dashboard.lovable.app',
    'github.com', 'raw.githubusercontent.com', 'api.github.com',
    'openai.com', 'platform.openai.com', 'developers.openai.com', 'api.openai.com',
    'play.google.com', 'trustpilot.com', 'www.trustpilot.com',
    'example.com', 'untrusted.invalid', 'localhost',
}
TOOL_TYPES = {'function_call', 'function_call_output', 'custom_tool_call', 'custom_tool_call_output'}
SECRET_PATTERN = re.compile(r'(?:sk-(?:proj-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})')
# These markers indicate runtime/agent context rather than a visible user request.
PRIVATE_CONTEXT = re.compile(r'<(?:environment_context|system|developer|skills_instructions|multi_agent_role)\b|Message Type: (?:NEW_TASK|MESSAGE|FINAL_ANSWER)|<\|(?:system|developer|analysis)\|>', re.I)
DOMAIN_PATTERN = re.compile(r'(?<![\w@.-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[A-Za-z]{2,63}(?![\w.-])')
FILE_SUFFIXES = {'md','kt','kts','py','cjs','mjs','js','ts','tsx','json','jsonl','csv','html','css','pdf','yaml','yml','sql','xml','png','jpg','txt','toml','lock','properties','sh','env'}
URL_PATTERN = re.compile(r'\b(?:https?|wss?|ftp|postgres(?:ql)?)://[^\s<>"\'`]+')


def load_secrets(root: Path) -> list[str]:
    """Read secret values locally; never print their values or names in reports."""
    values = []
    env = root / '.env'
    if env.exists():
        for line in env.read_text().splitlines():
            if '=' not in line or line.lstrip().startswith('#'):
                continue
            name, value = line.split('=', 1)
            value = value.strip().strip('"\'')
            if re.search(r'KEY|PASSWORD|SECRET|TOKEN', name, re.I) and len(value) >= 8:
                values.append(value)
    return sorted(set(values), key=len, reverse=True)


def scrub_text(text: str, secrets: list[str], root: Path = ROOT) -> tuple[str, collections.Counter]:
    changes = collections.Counter()
    for value in secrets:
        count = text.count(value)
        if count:
            text = text.replace(value, '[REDACTED CREDENTIAL]')
            changes['credential values'] += count
    text, count = SECRET_PATTERN.subn('[REDACTED CREDENTIAL]', text)
    changes['credential patterns'] += count
    # Retain project-relative paths, but never expose a host user's home layout.
    text = text.replace(str(root) + '/', '[project]/').replace(str(root), '[project]')
    text, count = re.subn(r'/(?:home|Users|root)/[^\s<>"\'`]+', '[REDACTED PRIVATE PATH]', text)
    changes['private filesystem paths'] += count
    text, count = re.subn(r'/(?:etc|var|opt|tmp|srv|run|mnt|media|data|private|workspace|workspaces)/[^\s<>"\'`]+', '[REDACTED HOST PATH]', text)
    changes['host configuration paths'] += count
    text, count = re.subn(r'(?:~|\$HOME|\$\{HOME\})/[^\s<>"\'`]+', '[REDACTED PRIVATE PATH]', text)
    changes['home-relative paths'] += count
    public_urls = []
    def url(match: re.Match) -> str:
        value = match.group(0)
        try:
            parsed = urlsplit(value)
            if parsed.username is not None or parsed.password is not None:
                changes['credential-bearing URLs'] += 1
                return '[REDACTED CREDENTIAL-BEARING URL]'
            if parsed.hostname not in PUBLIC_DOMAINS:
                changes['unrelated URL hosts'] += 1
                return '[REDACTED UNRELATED URL]'
        except ValueError:
            changes['unparseable URLs'] += 1
            return '[REDACTED URL]'
        public_urls.append(value)
        return f'PUBLIC_URL_PLACEHOLDER_{len(public_urls)-1}_END'
    text = URL_PATTERN.sub(url, text)
    def domain(match: re.Match) -> str:
        value = match.group(0)
        if value.lower() in PUBLIC_DOMAINS or value.rsplit('.', 1)[-1].lower() in FILE_SUFFIXES:
            return value
        changes['unrelated hostnames'] += 1
        return '[REDACTED UNRELATED HOST]'
    text, count = re.subn(r'\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b', '[REDACTED EMAIL ADDRESS]', text)
    changes['email addresses'] += count
    text = DOMAIN_PATTERN.sub(domain, text)
    text, count = re.subn(r'\b(?:\d{1,3}\.){3}\d{1,3}\b', '[REDACTED HOST ADDRESS]', text)
    changes['host addresses'] += count
    def ipv6(match: re.Match) -> str:
        try:
            ipaddress.IPv6Address(match.group(0).split('%', 1)[0])
        except ValueError:
            return match.group(0)
        changes['IPv6 addresses'] += 1
        return '[REDACTED HOST ADDRESS]'
    text = re.sub(r'(?<![\w:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[A-Za-z0-9_-]+)?', ipv6, text)
    for index, value in enumerate(public_urls):
        text = text.replace(f'PUBLIC_URL_PLACEHOLDER_{index}_END', value)
    return text, +changes


def sanitize_event(event: dict, secrets: list[str]) -> tuple[dict | None, collections.Counter]:
    changes = collections.Counter()
    if event.get('type') != 'response_item':
        return None, changes
    payload = event.get('payload', {})
    kind = payload.get('type')
    record = {'timestamp': event.get('timestamp'), 'type': 'response_item'}
    if kind in TOOL_TYPES:
        public = {'type': kind}
        for key in ('call_id', 'name', 'namespace'):
            if isinstance(payload.get(key), str):
                public[key] = payload[key]
        field = 'output' if kind.endswith('_output') else ('input' if kind == 'custom_tool_call' else 'arguments')
        public[field] = '[OMITTED FOR PUBLICATION: complete tool payload; may contain private context, credentials, host inventory or protected instructions.]'
        record['payload'] = public
        record['publication_redactions'] = {'complete tool payload omitted': 1}
        changes['complete tool payload omitted'] += 1
        return record, changes
    if kind != 'message':
        return None, changes
    role = payload.get('role')
    channel = payload.get('channel')
    phase = payload.get('phase')
    if role not in ('user', 'assistant') or channel in ('analysis', 'summary'):
        return None, changes
    if role == 'assistant' and phase not in ('commentary', 'final_answer') and channel not in ('commentary', 'final'):
        return None, changes
    public = {'type': 'message', 'role': role}
    if phase in ('commentary', 'final_answer'):
        public['phase'] = phase
    if channel in ('commentary', 'final'):
        public['channel'] = channel
    content = []
    for item in payload.get('content', []):
        if item.get('type') not in ('input_text', 'output_text') or not isinstance(item.get('text'), str):
            content.append({'type': 'publication_redaction', 'text': '[OMITTED FOR PUBLICATION: non-text attachment or internal content.]'})
            changes['non-text content omitted'] += 1
            continue
        value = item['text']
        if PRIVATE_CONTEXT.search(value):
            value = '[OMITTED FOR PUBLICATION: runtime or agent context; not a visible user request.]'
            changes['runtime or agent context omitted'] += 1
        else:
            value, updates = scrub_text(value, secrets)
            changes.update(updates)
        content.append({'type': item['type'], 'text': value})
    public['content'] = content
    record['payload'] = public
    if changes:
        record['publication_redactions'] = dict(changes)
    return record, changes


def write_atomic(path: Path, text: str) -> None:
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False, encoding='utf-8') as handle:
        handle.write(text)
        temp = Path(handle.name)
    temp.replace(path)


def export(source_dir: Path, out: Path) -> dict:
    sources = [path for path in source_dir.rglob('*.jsonl') if any(session in path.name for session in SESSIONS)]
    missing = [session for session in SESSIONS if not any(session in path.name for path in sources)]
    if missing:
        raise RuntimeError('Some configured session logs are unavailable; refusing to publish an incomplete replacement.')
    secrets = load_secrets(ROOT)
    snapshots = []
    for source in sorted(sources):
        records = []
        totals = collections.Counter()
        excluded = 0
        for line in source.read_text().splitlines():
            event = json.loads(line)
            public, changes = sanitize_event(event, secrets)
            if public is None:
                excluded += 1
            else:
                records.append(public)
                totals.update(changes)
        data = '\n'.join(json.dumps(record, ensure_ascii=False) for record in records) + '\n'
        if any(secret in data for secret in secrets) or SECRET_PATTERN.search(data):
            raise RuntimeError('Credential safety check failed; no new transcript will be published.')
        name = source.name.replace('rollout-', 'visible-')
        session_id = next(session for session in SESSIONS if session in source.name)
        snapshots.append((name, data, {
            'file': name, 'purpose': SESSIONS[session_id], 'recorded_events': len(records),
            'excluded_private_or_internal_events': excluded, 'publication_redactions': dict(totals),
            'sha256': hashlib.sha256(data.encode()).hexdigest(),
        }))
    out.mkdir(parents=True, exist_ok=True)
    manifest = {
        'format': 'privacy-filtered-visible-event-export-v2',
        'snapshot_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'complete_raw_transcript': False,
        'excluded': ['system/developer instructions', 'private reasoning', 'internal context and bookkeeping',
                     'all tool arguments and result bodies', 'private host inventory and cross-project filesystem paths',
                     'credential values and credential-shaped tokens'],
        'original_logs': 'Preserved unchanged in local Codex session storage; not included in this public repository.',
        'sessions': [entry for _, _, entry in snapshots],
    }
    for name, data, _ in snapshots:
        write_atomic(out / name, data)
    write_atomic(out / 'manifest.json', json.dumps(manifest, indent=2) + '\n')
    return manifest


def self_test() -> None:
    sentinel = 'private-value-for-regression-test'
    message = {'type': 'response_item', 'payload': {'type': 'message', 'role': 'assistant', 'phase': 'commentary', 'internal_chat_message_metadata_passthrough': 'DO_NOT_EXPORT', 'content': [{'type': 'output_text', 'text': f'Key {sentinel}; host https://unrelated.example.net; email someone@unrelated.example.net; /home/someone/other-project/file; /etc/private/config'}]}}
    sanitized, _ = sanitize_event(message, [sentinel])
    encoded = json.dumps(sanitized)
    assert all(value not in encoded for value in (sentinel, 'unrelated.example.net', '/home/someone', '/etc/private', 'DO_NOT_EXPORT'))
    tool = {'type': 'response_item', 'payload': {'type': 'function_call_output', 'call_id': 'test', 'output': '<system>DO_NOT_EXPORT</system> private inventory'}}
    sanitized, _ = sanitize_event(tool, [])
    assert 'DO_NOT_EXPORT' not in json.dumps(sanitized) and 'OMITTED FOR PUBLICATION' in json.dumps(sanitized)
    for role, phase, channel in [('system', None, None), ('developer', None, None), ('assistant', None, 'analysis')]:
        item = {'type': 'response_item', 'payload': {'type': 'message', 'role': role, 'phase': phase, 'channel': channel, 'content': [{'type': 'output_text', 'text': 'DO_NOT_EXPORT'}]}}
        assert sanitize_event(item, [])[0] is None
    context = {'type': 'response_item', 'payload': {'type': 'message', 'role': 'user', 'content': [{'type': 'input_text', 'text': '<environment_context>DO_NOT_EXPORT</environment_context>'}]}}
    assert 'DO_NOT_EXPORT' not in json.dumps(sanitize_event(context, [])[0])
    text, _ = scrub_text('Visit https://reviq.xyz and https://play.google.com/store/apps/details?id=org.example.app.', [])
    assert 'https://reviq.xyz' in text and 'https://play.google.com/store/apps/details?id=org.example.app' in text
    probe, _ = scrub_text('private.example.museum https://private.example.cloud/a /srv/private/site ~/other/repo 2001:db8::42 https://user:pass@github.com/repo', [])
    assert not any(value in probe for value in ('example.museum', 'example.cloud', '/srv/', '~/other', '2001:db8::42', 'user:pass'))
    print('Transcript publication regression checks passed.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        codex_dir = Path(os.environ.get('CODEX_HOME', str(Path.home() / '.codex')))
        manifest = export(codex_dir / 'sessions', ROOT / 'ai-transcripts')
        print(f"Exported {len(manifest['sessions'])} privacy-filtered session snapshots; originals remain unchanged.")
