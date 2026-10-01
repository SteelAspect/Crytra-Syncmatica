import sqlite3

from cytra_syncmatica_bot.db import lookup_shared_link
from helpers import make_db


async def test_links_are_one_per_user_and_per_account():
    db = await make_db()
    try:
        await db.set_link(1, "AAAA", "Alice")
        await db.set_link(2, "BBBB", "Bob")
        await db.set_link(3, "aaaa", "Alice")  # Alice linked a second Discord user: the first link goes
        assert await db.get_link_by_discord(1) is None
        assert (await db.get_link_by_discord(3)).mc_name == "Alice"
        assert (await db.get_link_by_uuid("AAAA")).discord_id == 3
        assert await db.delete_link(2) and not await db.delete_link(2)
        assert len(await db.all_links()) == 1
    finally:
        await db.close()


async def test_feed_messages_round_trip():
    db = await make_db()
    try:
        await db.set_feed_message("sid", 5, 6)
        await db.set_feed_message("sid", 5, 7)
        assert (await db.get_feed_message("sid")).message_id == 7
        assert len(await db.all_feed_messages()) == 1
        await db.delete_feed_message("sid")
        assert await db.get_feed_message("sid") is None
    finally:
        await db.close()


def test_shared_cytra_bridge_lookup_is_read_only_and_tolerant(tmp_path):
    assert lookup_shared_link("", 1) is None
    assert lookup_shared_link(str(tmp_path / "missing.sqlite3"), 1) is None
    path = tmp_path / "bridge.sqlite3"
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE players (uuid TEXT, ign TEXT, discord_id INTEGER)")
    conn.execute("INSERT INTO players VALUES ('069A79F4-44E9-4726-A5BE-FCA90E38AAF5', 'Notch', 42)")
    conn.commit(); conn.close()
    assert lookup_shared_link(str(path), 42) == ("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch")
    assert lookup_shared_link(str(path), 43) is None
