import pytest

from cytra_syncmatica_bot.config import ConfigError, load_config


def test_loads_with_env_substitution(tmp_path, monkeypatch):
    monkeypatch.setenv("DISCORD_TOKEN", "tok")
    (tmp_path / ".env").write_text("CYTRA_LINK_SECRET=sec\n")
    (tmp_path / "config.yaml").write_text("""
discord:
  token: ${DISCORD_TOKEN}
  guild_id: 42
  feed_channel_id: 7
server:
  host: mc.example.com
  secret: ${CYTRA_LINK_SECRET}
syncmatica:
  page_size: 10
""")
    cfg = load_config(tmp_path / "config.yaml")
    assert cfg.discord.token == "tok" and cfg.server.secret == "sec"
    assert cfg.syncmatica.page_size == 10 and cfg.server.port == 25565
    assert cfg.database.endswith("syncmatica-bot.sqlite3")


@pytest.mark.parametrize("body,msg", [
    ("discord:\n  token: t\n  guild_id: 1\nserver:\n  host: ''\n  secret: s\n", "server.host"),
    ("discord:\n  token: t\n  guild_id: 1\nserver:\n  host: h\n  secret: ''\n", "server.secret"),
    ("discord:\n  token: t\n  guild_id: 0\nserver:\n  host: h\n  secret: s\n", "guild_id"),
    ("discord:\n  token: t\n  guild_id: 1\nserver:\n  host: h\n  secret: s\nsyncmatica:\n  page_size: 40\n", "page_size"),
])
def test_rejects_bad_values(tmp_path, body, msg):
    (tmp_path / "config.yaml").write_text(body)
    with pytest.raises(ConfigError, match=msg):
        load_config(tmp_path / "config.yaml")
