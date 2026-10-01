import importlib
import re

import discord
from discord.ext import commands

from cytra_syncmatica_bot.core import COGS
from expected_commands import COG_CLASSES, COG_MODULES, COMMANDS, EXPECTED_COMMANDS
from helpers import make_cfg

_NAME_RE = re.compile(r"^[-_\w]{1,32}$")


def _all_command_names():
    names = []
    for mod_name in COG_MODULES:
        cls = getattr(importlib.import_module(mod_name), COG_CLASSES[mod_name])
        for cmd in getattr(cls, "__cog_app_commands__", []):
            names.append(cmd.name)
    return names


def test_registry_matches_core_cog_list():
    assert COG_MODULES == COGS


def test_each_cog_registers_exactly_its_declared_commands():
    for mod_name in COG_MODULES:
        cls_name = COG_CLASSES[mod_name]
        cls = getattr(importlib.import_module(mod_name), cls_name)
        names = {cmd.name for cmd in getattr(cls, "__cog_app_commands__", [])}
        assert names == COMMANDS[cls_name], f"{cls_name}: {names}"
    assert set(_all_command_names()) == EXPECTED_COMMANDS


def test_names_are_valid_and_unique():
    names = _all_command_names()
    assert len(names) == len(set(names))
    for n in names:
        assert _NAME_RE.match(n) and n == n.lower()


async def test_commands_register_into_a_real_tree():
    bot = commands.Bot(command_prefix="!", intents=discord.Intents.none())
    bot.cfg = make_cfg()
    try:
        for mod_name in COG_MODULES:
            cls = getattr(importlib.import_module(mod_name), COG_CLASSES[mod_name])
            cog = cls.__new__(cls)
            cog.bot = bot
            cog.cfg = bot.cfg
            await bot.add_cog(cog)
        guild = discord.Object(id=1)
        bot.tree.copy_global_to(guild=guild)
        assert len(bot.tree.get_commands(guild=guild)) == len(_all_command_names())
    finally:
        await bot.close()
