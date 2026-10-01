"""Which cog registers which slash commands (groups count once, by group name)."""

COG_CLASSES: dict[str, str] = {
    "cytra_syncmatica_bot.cogs.linking": "Linking",
    "cytra_syncmatica_bot.cogs.schematics": "Schematics",
    "cytra_syncmatica_bot.cogs.projects": "Projects",
    "cytra_syncmatica_bot.cogs.feed": "Feed",
}

COMMANDS: dict[str, set[str]] = {
    "Linking": {"link", "unlink", "whoami", "links"},
    "Schematics": {"schematic", "materials"},
    "Projects": {"project"},
    "Feed": set(),
}

COG_MODULES = list(COG_CLASSES)
EXPECTED_COMMANDS: set[str] = set().union(*COMMANDS.values())
