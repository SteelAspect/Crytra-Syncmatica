"""Console + rotating file logging."""

from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path

FORMAT = "%(asctime)s %(levelname)-5s [%(name)s] %(message)s"
DATEFMT = "%Y-%m-%d %H:%M:%S"


def setup_logging(level: str = "INFO", file_path: str | Path | None = None) -> None:
    formatter = logging.Formatter(FORMAT, DATEFMT)
    root = logging.getLogger()
    root.setLevel(level.upper())
    console = logging.StreamHandler()
    console.setFormatter(formatter)
    root.addHandler(console)
    if file_path:
        path = Path(file_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        fh = RotatingFileHandler(path, maxBytes=5 * 1024 * 1024, backupCount=5, encoding="utf-8")
        fh.setFormatter(formatter)
        root.addHandler(fh)
    if level.upper() != "DEBUG":
        logging.getLogger("discord").setLevel(logging.WARNING)
