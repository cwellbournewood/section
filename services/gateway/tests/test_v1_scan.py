"""Tests for /v1/scan and /v1/restore — edge-client API."""
from __future__ import annotations

import os
import re
import tempfile
from pathlib import Path

import httpx
import pytest
from httpx import ASGITransport

# Test config — must be set before importing the app/config.
os.environ["SECTION_ENV"] = "development"
os.environ["DATABASE_URL"] = "sqlite+aiosqlite:///:memory:"
os.environ["REDIS_URL"] = ""  # in-memory vault
os.environ["SECTION_API_KEYS"] = "test-key"


def _write_bundle(tmp: Path, *, block_aws: bool = False) -> Path:
    bundle = tmp / "bundle"
    (bundle / "policies").mkdir(parents=True)
    (bundle / "manifest.yaml").write_text(
        "apiVersion: section/v1\nkind: Bundle\nmetadata: {name: t, version: '0'}\nspec: {includes: []}\n"
    )
    (bundle / "models.yaml").write_text(
        "apiVersion: section/v1\nkind: ModelRegistry\nspec:\n"
        "  models: []\n  endpoints: []\n"
    )
    (bundle / "routes.yaml").write_text(
        "apiVersion: section/v1\nkind: Routes\nspec: []\n"
    )
    if block_aws:
        (bundle / "policies" / "0001.yaml").write_text(
            "apiVersion: section/v1\n"
            "kind: Policy\n"
            "metadata: {id: sec, name: sec}\n"
            "spec:\n"
            "  match: {routes: ['/v1/scan']}\n"
            "  detect: {enable: [credential.aws_access_key]}\n"
            "  decide:\n"
            "    rules:\n"
            "      - when: \"any(findings, .label == 'credential.aws_access_key')\"\n"
            "        action: block\n"
            "        reason: 'AWS credential not permitted'\n"
            "        severity: high\n"
            "  fail_mode: closed\n"
        )
    else:
        (bundle / "policies" / "0001.yaml").write_text(
            "apiVersion: section/v1\n"
            "kind: Policy\n"
            "metadata: {id: pii, name: pii}\n"
            "spec:\n"
            "  match: {routes: ['/v1/scan']}\n"
            "  detect: {enable: [pii.email]}\n"
            "  decide:\n"
            "    rules:\n"
            "      - when: \"any(findings, .label == 'pii.email')\"\n"
            "        action: transform\n"
            "        transforms:\n"
            "          - {label: pii.email, method: tokenise, scope: request, ttl: 1h}\n"
            "      - when: 'true'\n"
            "        action: allow\n"
            "  fail_mode: closed\n"
        )
    return bundle


def _set_bundle(tmp: Path, **kwargs) -> None:
    bundle = _write_bundle(tmp, **kwargs)
    os.environ["SECTION_POLICY_BUNDLE"] = str(bundle)
    # Point each test at its own file-backed SQLite database rather than
    # ":memory:". With sqlite+aiosqlite a ":memory:" database is scoped to the
    # connection that opened it, so rows written by the batched AuditWriter are
    # not reliably visible to the /admin/events read path — on CI the two
    # audit-reading tests below failed with events=[] even after draining the
    # writer with flush() and polling for six seconds. A file in the per-test
    # temp dir keeps the same isolation but makes writes durable and visible
    # regardless of which connection performs the read.
    os.environ["DATABASE_URL"] = f"sqlite+aiosqlite:///{(tmp / 'section-test.db').as_posix()}"
    from section_gateway.config import get_settings
    get_settings.cache_clear()


@pytest.mark.asyncio
async def test_scan_masks_email_and_round_trips_via_restore():
    """Happy path: scan masks an email, restore swaps it back."""
    tmp = Path(tempfile.mkdtemp())
    _set_bundle(tmp)

    from section_gateway.main import create_app

    app = create_app()
    async with httpx.AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        async with app.router.lifespan_context(app):
            resp = await client.post(
                "/v1/scan",
                headers={"x-api-key": "test-key", "x-section-tenant": "default"},
                json={
                    "text": "Please email alice@example.com about the budget.",
                    "client": "browser-extension",
                    "url": "https://chatgpt.com/c/abc",
                    "model": "gpt-4o",
                    "session_id": "tab-1",
                },
            )
            assert resp.status_code == 200, resp.text
            body = resp.json()
            assert body["action"] == "mask"
            assert "alice@example.com" not in body["sanitised"]
            assert re.search(r"<EMAIL_[A-Z2-7]{4}>", body["sanitised"])
            assert len(body["transforms"]) == 1
            assert body["transforms"][0]["label"] == "pii.email"
            assert body["transforms"][0]["method"] == "tokenise"
            request_id = body["request_id"]
            placeholder = body["transforms"][0]["placeholder"]

            # Now ask /v1/restore to swap the placeholder back. Use the
            # model's reply (simulated) that mentions the placeholder.
            restore_resp = await client.post(
                "/v1/restore",
                headers={"x-api-key": "test-key", "x-section-tenant": "default"},
                json={
                    "request_id": request_id,
                    "text": f"Sure, I'll email {placeholder} about it.",
                },
            )
            assert restore_resp.status_code == 200, restore_resp.text
            rb = restore_resp.json()
            assert "alice@example.com" in rb["text"]
            assert rb["restored"] == 1
            assert rb["missing"] == []


@pytest.mark.asyncio
async def test_scan_blocks_aws_secret():
    """Block path: scan returns action=block and no sanitised body."""
    tmp = Path(tempfile.mkdtemp())
    _set_bundle(tmp, block_aws=True)

    from section_gateway.main import create_app

    app = create_app()
    async with httpx.AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        async with app.router.lifespan_context(app):
            resp = await client.post(
                "/v1/scan",
                headers={"x-api-key": "test-key", "x-section-tenant": "default"},
                json={
                    "text": "deploy with AKIAIOSFODNN7EXAMPLE please",
                    "client": "vscode",
                },
            )
            assert resp.status_code == 200, resp.text
            body = resp.json()
            assert body["action"] == "block"
            assert body["sanitised"] is None
            assert body["reason"] == "AWS credential not permitted"
            assert body["severity"] == "high"


@pytest.mark.asyncio
async def test_scan_allow_when_clean():
    """No findings → action=allow, sanitised echoes input."""
    tmp = Path(tempfile.mkdtemp())
    _set_bundle(tmp)

    from section_gateway.main import create_app

    app = create_app()
    async with httpx.AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        async with app.router.lifespan_context(app):
            resp = await client.post(
                "/v1/scan",
                headers={"x-api-key": "test-key"},
                json={"text": "what is 2 + 2", "client": "cli"},
            )
            assert resp.status_code == 200, resp.text
            body = resp.json()
            assert body["action"] == "allow"
            assert body["sanitised"] == "what is 2 + 2"
            assert body["transforms"] == []


@pytest.mark.asyncio
async def test_restore_reports_missing_for_unknown_placeholder():
    """Restore with a placeholder we never minted reports it as missing."""
    tmp = Path(tempfile.mkdtemp())
    _set_bundle(tmp)

    from section_gateway.main import create_app

    app = create_app()
    async with httpx.AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        async with app.router.lifespan_context(app):
            resp = await client.post(
                "/v1/restore",
                headers={"x-api-key": "test-key"},
                json={
                    "request_id": "00000000-0000-0000-0000-000000000000",
                    "text": "the value is <EMAIL_A2B3> apparently",
                },
            )
            assert resp.status_code == 200, resp.text
            body = resp.json()
            assert body["restored"] == 0
            assert body["missing"] == ["<EMAIL_A2B3>"]


@pytest.mark.asyncio
async def test_scan_writes_audit_with_edge_source_tag():
    """The audit row carries an `edge_source` transforms entry tagging client/url."""
    tmp = Path(tempfile.mkdtemp())
    _set_bundle(tmp)

    from section_gateway.main import create_app

    app = create_app()
    async with httpx.AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        async with app.router.lifespan_context(app):
            resp = await client.post(
                "/v1/scan",
                headers={"x-api-key": "test-key"},
                json={
                    "text": "send mail to bob@example.com",
                    "client": "browser-extension",
                    "url": "https://claude.ai/chat/xyz",
                    "model": "claude-3-5-sonnet",
                },
            )
            assert resp.status_code == 200
            request_id = resp.json()["request_id"]

            # Drain the audit queue + wait for the background writer to commit.
            # Re-flush inside the loop because the request handler may complete
            # before its audit enqueue finishes — on a slow runner, the first
            # flush sees an empty queue but a row appears moments later.
            import asyncio as _asyncio
            for _ in range(300):  # up to ~6s; CI runners need the headroom
                await app.state.section.audit.flush()
                events_resp = await client.get(
                    "/admin/events", headers={"x-api-key": "test-key"}
                )
                events = events_resp.json()
                if any(e["request_id"] == request_id for e in events):
                    break
                await _asyncio.sleep(0.02)
            assert events_resp.status_code == 200, events_resp.text
            our = next((e for e in events if e["request_id"] == request_id), None)
            assert our is not None, f"row not found, events={events}"
            edge_tag = next(
                (t for t in our["transforms"] if t.get("method") == "edge_source"),
                None,
            )
            assert edge_tag is not None
            assert edge_tag["client"] == "browser-extension"
            assert edge_tag["url"] == "https://claude.ai/chat/xyz"
            assert edge_tag["model_hint"] == "claude-3-5-sonnet"


@pytest.mark.asyncio
async def test_scan_normalises_unknown_client_value():
    """A client value not in the allowlist coerces to edge-unknown."""
    tmp = Path(tempfile.mkdtemp())
    _set_bundle(tmp)

    from section_gateway.main import create_app

    app = create_app()
    async with httpx.AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        async with app.router.lifespan_context(app):
            resp = await client.post(
                "/v1/scan",
                headers={"x-api-key": "test-key"},
                json={"text": "hi", "client": "malicious-injector"},
            )
            assert resp.status_code == 200
            request_id = resp.json()["request_id"]

            # Re-flush inside the loop — see the sibling test's comment.
            import asyncio as _asyncio
            for _ in range(300):  # up to ~6s; CI runners need the headroom
                await app.state.section.audit.flush()
                events_resp = await client.get(
                    "/admin/events", headers={"x-api-key": "test-key"}
                )
                events = events_resp.json()
                if any(e["request_id"] == request_id for e in events):
                    break
                await _asyncio.sleep(0.02)
            assert events_resp.status_code == 200
            our = next((e for e in events if e["request_id"] == request_id), None)
            assert our is not None, f"row not found, events={events}"
            edge_tag = next(
                (t for t in our["transforms"] if t.get("method") == "edge_source"),
                None,
            )
            assert edge_tag is not None
            assert edge_tag["client"] == "edge-unknown"
