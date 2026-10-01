"""Cog behaviour against the fake mod: linking, the materials view's buttons,
the feed's in-place edits and resync, and the standalone bot's error texts."""

import asyncio

import pytest

from cytra_syncmatica_bot.cogs.feed import Feed
from cytra_syncmatica_bot.cogs.linking import Linking
from cytra_syncmatica_bot.cogs.schematics import MaterialsView, Schematics
from cytra_syncmatica_bot.core import NOT_INSTALLED, SyncmaticaBot
from cytra_syncmatica_bot.modlink import ExtensionMissing, ModLink
from helpers import OP_UUID, SCHEMATIC_ID, FakeChannel, FakeInteraction, make_cfg, make_db, make_member, start_fake_server


async def make_bot(with_extension=True):
    server, ext = await start_fake_server(with_extension)
    cfg = make_cfg()
    cfg.server.port = server.port
    db = await make_db()
    link = ModLink("127.0.0.1", server.port, server.secret, timeout=2.0)
    bot = SyncmaticaBot(cfg, db, link)
    bot.link.on_event(bot._on_link_event)
    await link.connect_once()
    return bot, server, ext


async def shutdown(bot, server):
    await bot.link.stop()
    await bot.db.close()
    await server.stop()


async def test_link_claims_a_code_and_whoami_reports_it():
    bot, server, ext = await make_bot()
    try:
        cog = Linking(bot)
        member = make_member(42)
        it = FakeInteraction(member, bot)
        await cog.link.callback(cog, it, "k7p2xq")
        assert "OpPlayer" in it.followup.sent[-1]["content"]
        assert await bot.resolve_player(42) == (OP_UUID, "OpPlayer")
        it2 = FakeInteraction(member, bot)
        await cog.link.callback(cog, it2, "k7p2xq")
        assert "expired" in it2.followup.sent[-1]["content"]
        it3 = FakeInteraction(member, bot)
        await cog.whoami.callback(cog, it3)
        assert "OpPlayer" in it3.response.messages[-1]["content"]
        it4 = FakeInteraction(member, bot)
        await cog.unlink.callback(cog, it4, None)
        assert await bot.resolve_player(42) is None
    finally:
        await shutdown(bot, server)


async def test_materials_view_buttons_send_the_linked_uuid_and_show_refusals_privately():
    bot, server, ext = await make_bot()
    try:
        await bot.db.set_link(42, OP_UUID, "OpPlayer")
        await bot.db.set_link(43, "66666666-7777-8888-9999-aaaaaaaaaaaa", "PlainPlayer")
        view = MaterialsView(bot, SCHEMATIC_ID, "Iron farm", False)
        await view.fetch()
        assert view.pages == 2 and len(view.items) == 3  # page_size 3 of 5 items
        view.selected = "minecraft:stone"
        it = FakeInteraction(make_member(42), bot)
        await view.act(it, "add", amount=64)
        assert ext.actions[-1]["mc_uuid"] == OP_UUID and ext.actions[-1]["action"] == "add"
        assert ext.items["minecraft:stone"][1] == 64
        assert it.response.edits and "64/100" in it.followup.sent[-1]["content"]

        it2 = FakeInteraction(make_member(43), bot)
        view.selected = "minecraft:stone"
        await view.act(it2, "reset")
        assert "not permitted" in it2.response.messages[-1]["content"] and it2.response.messages[-1]["ephemeral"]
        assert ext.items["minecraft:stone"][1] == 64, "the refused reset changed nothing"

        it3 = FakeInteraction(make_member(99), bot)
        await view.act(it3, "done")
        assert "Link your Minecraft account" in it3.response.messages[-1]["content"]

        it4 = FakeInteraction(make_member(42), bot)
        await view._turn(it4, 1)
        assert view.page == 1 and len(view.items) == 2

        # group filter: the select lists the groups, choosing one narrows the items
        assert [g["name"] for g in view.groups] == ["Stone", "Wood", "Glass"]
        it5 = FakeInteraction(make_member(42), bot, data={"values": ["Wood"]})
        await view._on_group(it5)
        assert view.group == "Wood" and view.page == 0
        assert {i["item"] for i in view.items} == {"minecraft:oak_planks", "minecraft:oak_door"}
        assert "Wood" in it5.response.edits[-1]["embed"].fields[0].name
        it6 = FakeInteraction(make_member(42), bot, data={"values": ["*"]})
        await view._on_group(it6)
        assert view.group is None and len(view.items) == 3
    finally:
        await shutdown(bot, server)


async def test_shopping_command_groups_lines_and_attaches_a_file_when_asked():
    bot, server, ext = await make_bot()
    try:
        ext.items["minecraft:glass"][1] = 10
        cog = Schematics(bot)
        it = FakeInteraction(make_member(1), bot)
        await cog.shopping.callback(cog, it, "Iron farm")
        embed = it.followup.sent[-1]["embed"]
        assert embed.title == "Shopping list: Iron farm" and "**128**" in embed.description
        assert [f.name for f in embed.fields] == ["Stone", "Wood"], "glass is complete, so no Glass section"
        assert "stone: `100`" in embed.fields[0].value
        it = FakeInteraction(make_member(1), bot)
        await cog.shopping.callback(cog, it, "Iron farm", group="Wood", as_file=True)
        sent = it.followup.sent[-1]
        assert sent["embed"].title == "Shopping list: Iron farm · Wood" and sent["file"].filename == "shopping-Iron_farm.txt"
        assert b"Shopping list for Iron farm" in sent["file"].fp.read()
        for item in ext.items.values():
            item[1] = item[0]
        it = FakeInteraction(make_member(1), bot)
        await cog.shopping.callback(cog, it, "Iron farm")
        assert it.followup.sent[-1]["embed"].description == "Nothing left to gather."
    finally:
        await shutdown(bot, server)


async def test_project_commands_manage_and_show_projects():
    from cytra_syncmatica_bot.cogs.projects import Projects
    bot, server, ext = await make_bot()
    try:
        await bot.db.set_link(42, OP_UUID, "OpPlayer")
        await bot.db.set_link(43, "66666666-7777-8888-9999-aaaaaaaaaaaa", "PlainPlayer")
        cog = Projects(bot)
        it = FakeInteraction(make_member(1), bot)
        await cog.list_.callback(cog, it)
        assert "No projects yet" in it.followup.sent[-1]["content"]

        it = FakeInteraction(make_member(42), bot)
        it.extras["player"] = (OP_UUID, "OpPlayer")
        await cog.create.callback(cog, it, "Base")
        assert "created as **OpPlayer**" in it.followup.sent[-1]["content"]
        assert ext.project_actions[-1]["mc_uuid"] == OP_UUID
        it = FakeInteraction(make_member(42), bot)
        it.extras["player"] = (OP_UUID, "OpPlayer")
        await cog.add.callback(cog, it, "Base", "Iron farm")
        assert list(ext.projects.values())[0]["members"] == [SCHEMATIC_ID]

        it = FakeInteraction(make_member(1), bot)
        await cog.info.callback(cog, it, "base")
        embed = it.followup.sent[-1]["embed"]
        assert embed.title == "Project: Base"
        fields = {f.name: f.value for f in embed.fields}
        assert "Iron farm" in fields["Schematics (1)"] and "Combined materials" in fields

        # the combined materials view targets the project and every edit carries project_id
        view = MaterialsView(bot, "p1", "Base", False, target_key="project_id")
        await view.fetch()
        assert view.schematic_name == "Base" and view.items[0]["parts"][0]["schematic"] == "Iron farm"
        view.selected = "minecraft:stone"
        it = FakeInteraction(make_member(42), bot)
        await view.act(it, "add", amount=10)
        assert ext.actions[-1]["project_id"] == "p1" and ext.items["minecraft:stone"][1] == 10

        it = FakeInteraction(make_member(1), bot)
        await cog.shopping.callback(cog, it, "Base")
        assert it.followup.sent[-1]["embed"].title == "Shopping list: Base"
        it = FakeInteraction(make_member(1), bot)
        await cog.where.callback(cog, it, "Base")
        assert it.followup.sent[-1]["embed"].fields[0].name == "Iron farm"

        # a plain player may not manage projects: the mod's refusal is shown privately by the error handler path
        it = FakeInteraction(make_member(43), bot)
        it.extras["player"] = ("66666666-7777-8888-9999-aaaaaaaaaaaa", "PlainPlayer")
        try:
            await cog.delete.callback(cog, it, "Base")
        except Exception as exc:  # the cog lets LinkError propagate to the app-command error handler
            assert "not permitted" in str(exc)
        assert ext.projects, "still there"
    finally:
        await shutdown(bot, server)


async def test_preview_command_attaches_the_png():
    bot, server, ext = await make_bot()
    try:
        cog = Schematics(bot)
        it = FakeInteraction(make_member(1), bot)
        await cog.preview.callback(cog, it, "Iron farm")
        sent = it.followup.sent[-1]
        assert sent["embed"].title == "Preview: Iron farm" and sent["file"].filename == "preview.png"
        assert sent["file"].fp.read(8) == b"\x89PNG\r\n\x1a\n"
        assert sent["embed"].image.url == "attachment://preview.png"
    finally:
        await shutdown(bot, server)


async def test_layers_command_shows_build_progress_per_layer():
    bot, server, ext = await make_bot()
    try:
        cog = Schematics(bot)
        it = FakeInteraction(make_member(1), bot)
        await cog.layers.callback(cog, it, "Iron farm")
        embed = it.followup.sent[-1]["embed"]
        assert embed.title == "Layers: Iron farm" and "2 of 4 layers done" in embed.description and "50.0%" in embed.description
        value = embed.fields[0].value
        assert value.splitlines()[0].startswith("▫️ **Y 67**") and "✅ **Y 64**" in value
    finally:
        await shutdown(bot, server)


async def test_groups_command_lists_progress_per_group():
    bot, server, ext = await make_bot()
    try:
        ext.items["minecraft:glass"][1] = 10
        cog = Schematics(bot)
        it = FakeInteraction(make_member(1), bot)
        await cog.groups.callback(cog, it, "Iron farm")
        embed = it.followup.sent[-1]["embed"]
        assert embed.title == "Material groups: Iron farm"
        value = embed.fields[0].value
        assert "✅ **Glass**" in value and "▫️ **Stone**" in value and "2 items" in value
    finally:
        await shutdown(bot, server)


async def test_schematic_commands_render_embeds_and_hidden_coordinates():
    bot, server, ext = await make_bot()
    try:
        cog = Schematics(bot)
        it = FakeInteraction(make_member(1), bot)
        await cog.list_.callback(cog, it)
        assert "Iron farm" in it.followup.sent[-1]["embed"].description
        it = FakeInteraction(make_member(1), bot)
        await cog.where.callback(cog, it, "Iron farm")
        fields = {f.name: f.value for f in it.followup.sent[-1]["embed"].fields}
        assert "120 64 -340" in fields["Origin"]
        ext.hide_coordinates = True
        it = FakeInteraction(make_member(1), bot)
        await cog.where.callback(cog, it, "Iron farm")
        fields = {f.name: f.value for f in it.followup.sent[-1]["embed"].fields}
        assert "hidden" in fields["Coordinates"] and "Origin" not in fields
    finally:
        await shutdown(bot, server)


async def test_feed_edits_one_message_per_schematic_and_refreshes_on_resync():
    bot, server, ext = await make_bot()
    try:
        channel = FakeChannel(555)
        bot.channel = lambda cid: channel if cid == 555 else None  # type: ignore[method-assign]
        feed = Feed(bot)
        await feed.on_link_event("schematic_shared", {"schematic": ext.schematic()}, 1)
        assert channel.sent[-1]["embed"].title == "Iron farm"
        assert channel.sent[-1]["file"].filename == "preview.png", "the share post carries the preview"
        await feed.on_link_event("list_created", {"schematic": ext.schematic(), "top_remaining": []}, 2)
        first = await bot.db.get_feed_message(SCHEMATIC_ID)
        assert first is not None and len(channel.sent) == 2
        ext.items["minecraft:stone"][1] = 50
        await feed.on_link_event("item_changed", {"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm", "changes": []}, 3)
        assert len(channel.sent) == 2, "edited in place, not posted again"
        edited = channel.messages[first.message_id].edits[-1]["embed"]
        assert "50" in edited.description
        await feed.on_link_event("item_completed", {"schematic": "Iron farm", "item": ext.entry("minecraft:glass"), "editor": {"name": "OpPlayer"}}, 4)
        assert "complete" in channel.sent[-1]["content"]
        ext.items["minecraft:glass"][1] = 10
        await feed.on_link_event("group_completed", {"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm",
                                                     "group": ext.groups()[2], "editor": {"name": "OpPlayer"}}, 4)
        assert "Group **Glass**" in channel.sent[-1]["content"] and "OpPlayer" in channel.sent[-1]["content"]
        assert len(channel.sent) == 4, "the materials message was edited, not re-posted"
        # the message vanished (deleted by a mod): resync posts a fresh one and re-tracks it
        channel.messages.clear()
        await feed.on_link_event("resync", {"queued_events_sent": 0, "schematics": 1}, 5)
        again = await bot.db.get_feed_message(SCHEMATIC_ID)
        assert again.message_id != first.message_id
        await feed.on_link_event("schematic_removed", {"id": SCHEMATIC_ID, "name": "Iron farm"}, 6)
        assert await bot.db.get_feed_message(SCHEMATIC_ID) is None
        await feed.on_link_event("project_changed", {"action": "created", "project": {"id": "p1", "name": "Base", "members": []}, "by": {"name": "OpPlayer"}}, 7)
        assert "Project **Base** created" in channel.sent[-1]["content"]
        await feed.on_link_event("project_completed", {"project": {"id": "p1", "name": "Base", "members": [1]}, "editor": {"name": "OpPlayer"}}, 8)
        assert "project **Base**" in channel.sent[-1]["content"]
        before = len(channel.sent)
        await feed.on_link_event("layer_completed", {"schematic": "Iron farm", "layer": {"y": 64}, "layers_complete": 1, "layers_total": 4}, 9)
        assert len(channel.sent) == before, "layer posts are off by default"
        bot.cfg.syncmatica.announce_layer_completed = True
        await feed.on_link_event("layer_completed", {"schematic": "Iron farm", "layer": {"y": 64}, "layers_complete": 1, "layers_total": 4}, 10)
        assert "Layer **Y 64**" in channel.sent[-1]["content"]
    finally:
        await shutdown(bot, server)


async def test_server_without_the_mod_gives_the_not_installed_message():
    bot, server, ext = await make_bot(with_extension=False)
    try:
        with pytest.raises(ExtensionMissing, match="isn't installed"):
            await bot.ext("ping")
        cog = Schematics(bot)
        it = FakeInteraction(make_member(1), bot)
        try:
            await cog.list_.callback(cog, it)
        except ExtensionMissing as exc:
            from discord import app_commands
            await bot._on_app_command_error(it, app_commands.CommandInvokeError(cog.list_, exc))
        assert it.followup.sent[-1]["content"] == NOT_INSTALLED
    finally:
        await shutdown(bot, server)
