import asyncio

import pytest

from cytra_syncmatica_bot.modlink import ExtensionMissing, LinkError, ModLink
from helpers import start_fake_server


async def connected(server) -> ModLink:
    link = ModLink("127.0.0.1", server.port, server.secret, timeout=2.0)
    await link.connect_once()
    return link


async def test_welcome_lists_extension_and_ext_requests_work():
    server, ext = await start_fake_server()
    link = await connected(server)
    try:
        assert link.has_extension()
        out = await link.ext("ping")
        assert out["protocol"] == 1
        out = await link.ext("get_materials", {"schematic": "Iron farm", "limit": 2})
        assert out["total"] == 5 and len(out["items"]) == 2
    finally:
        await link.stop(); await server.stop()


async def test_server_without_extension_is_reported_cleanly():
    server, _ = await start_fake_server(with_extension=False)
    link = await connected(server)
    try:
        assert not link.has_extension()
        with pytest.raises(ExtensionMissing):
            await link.ext("ping")
    finally:
        await link.stop(); await server.stop()


async def test_mod_errors_become_link_errors_with_the_message():
    server, _ = await start_fake_server()
    link = await connected(server)
    try:
        with pytest.raises(LinkError, match="unknown schematic nope"):
            await link.ext("get_schematic", {"schematic": "nope"})
    finally:
        await link.stop(); await server.stop()


async def test_events_are_dispatched_to_callbacks():
    server, _ = await start_fake_server()
    link = await connected(server)
    got = []
    link.on_event(lambda ns, t, p, ts: got.append((ns, t, p)))
    try:
        await server.event("cytra-syncmatica", "item_completed", {"schematic": "Iron farm"})
        await asyncio.sleep(0.05)
        assert got == [("cytra-syncmatica", "item_completed", {"schematic": "Iron farm"})]
        assert (await link.request("ping"))["ok"]
    finally:
        await link.stop(); await server.stop()


async def test_wrong_secret_is_an_auth_error():
    from cytra_syncmatica_bot.modlink import LinkAuthError

    server, _ = await start_fake_server()
    link = ModLink("127.0.0.1", server.port, "wrong", timeout=2.0)
    try:
        with pytest.raises(LinkAuthError):
            await link.connect_once()
    finally:
        await link.stop(); await server.stop()
