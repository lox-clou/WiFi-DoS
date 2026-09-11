#!/usr/bin/env bash
pip install -q telethon fastapi uvicorn
nohup uvicorn tg.server:app --host 0.0.0.0 --port 8787 > tg.log 2>&1 &
echo "bridge up on port 8787 — make it public in PORTS tab"
