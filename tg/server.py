# language: Python, file: tg/server.py, target: Codespaces Linux, uvicorn
import os, json
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from telethon import TelegramClient
from telethon.errors import SessionPasswordNeededError
from telethon.tl import functions

BASE = os.path.dirname(os.path.abspath(__file__))
CFG = os.path.join(BASE, "config.json")
SESSION = os.path.join(BASE, "twks_session")

app = FastAPI()
client = None
GIFT_REQ = None
GIFT_METHODS = []

def discover_gifts():
    global GIFT_REQ, GIFT_METHODS
    GIFT_METHODS = []
    GIFT_REQ = None
    for mod_name in ("messages", "payments", "stars", "users", "gifts"):
        mod = getattr(functions, mod_name, None)
        if mod is None:
            continue
        for name in dir(mod):
            if name.endswith("Request") and "Gift" in name:
                GIFT_METHODS.append(f"{mod_name}.{name}")
                if name == "GetSavedStarGiftsRequest":
                    GIFT_REQ = getattr(mod, name)
                elif GIFT_REQ is None and ("GetUserStarGifts" in name or "UserGifts" in name):
                    GIFT_REQ = getattr(mod, name)
    if GIFT_REQ is None:
        for mod_name in ("messages", "payments"):
            mod = getattr(functions, mod_name, None)
            if mod is None:
                continue
            for name in dir(mod):
                if name.endswith("Request") and ("UserStarGifts" in name or "UserGifts" in name or name == "GetSavedStarGiftsRequest"):
                    GIFT_REQ = getattr(mod, name)
                    break
            if GIFT_REQ is not None:
                break
    return GIFT_REQ

discover_gifts()

def load_cfg():
    try:
        with open(CFG) as f:
            return json.load(f)
    except Exception:
        return {}

class Cfg(BaseModel):
    api_id: int
    api_hash: str

class Start(BaseModel):
    phone: str

class CodeIn(BaseModel):
    phone: str
    code: str
    hash: str

class TwoFa(BaseModel):
    phone: str
    hash: str
    password: str

async def ensure_client():
    global client
    if client is None:
        cfg = load_cfg()
        if not cfg.get("api_id"):
            raise HTTPException(500, "not configured")
        client = TelegramClient(SESSION, int(cfg["api_id"]), cfg["api_hash"])
        await client.connect()
    return client

@app.get("/status")
async def status():
    cfg = load_cfg()
    logged = False
    me = None
    try:
        c = await ensure_client()
        if await c.is_user_authorized():
            logged = True
            m = await c.get_me()
            me = m.username or m.phone
    except Exception:
        pass
    return {
        "configured": bool(cfg.get("api_id")),
        "logged_in": logged,
        "me": me,
        "gift_req": getattr(GIFT_REQ, "__name__", None),
        "gift_methods": GIFT_METHODS[:60],
    }

@app.post("/config")
async def config(c: Cfg):
    with open(CFG, "w") as f:
        json.dump({"api_id": c.api_id, "api_hash": c.api_hash}, f)
    global client
    if client is not None:
        try: await client.disconnect()
        except Exception: pass
        client = None
    return {"ok": True}

@app.post("/login/start")
async def login_start(s: Start):
    c = await ensure_client()
    sent = await c.send_code_request(s.phone)
    return {"hash": sent.phone_code_hash}

@app.post("/login/code")
async def login_code(i: CodeIn):
    c = await ensure_client()
    try:
        await c.sign_in(phone=i.phone, code=i.code, phone_code_hash=i.hash)
    except SessionPasswordNeededError:
        return {"need_2fa": True}
    m = await c.get_me()
    return {"ok": True, "me": m.username or m.phone}

@app.post("/login/2fa")
async def login_2fa(i: TwoFa):
    c = await ensure_client()
    await c.sign_in(phone=i.phone, password=i.password, phone_code_hash=i.hash)
    m = await c.get_me()
    return {"ok": True, "me": m.username or m.phone}

@app.get("/gifts")
async def gifts(username: str):
    c = await ensure_client()
    if not await c.is_user_authorized():
        raise HTTPException(401, "not logged in")
    req = GIFT_REQ or discover_gifts()
    if req is None:
        raise HTTPException(501, f"telethon schema has no gifts request; found: {GIFT_METHODS}")
    username = username.lstrip("@")
    try:
        user = await c.get_entity(username)
    except Exception as e:
        raise HTTPException(404, f"user not found: {e}")
    res = None
    for kwargs in (
        {"user": user, "offset": 0, "limit": 100, "hash": 0},
        {"user": user, "offset": 0, "limit": 100},
        {"user": user},
    ):
        try:
            res = await c(req(**kwargs))
            break
        except TypeError:
            continue
        except Exception as e:
            raise HTTPException(500, f"gifts call failed: {e}")
    if res is None:
        raise HTTPException(500, "no call signature matched for gifts request")
    gifts_list = getattr(res, "gifts", None) or getattr(res, "user_gifts", None) or []
    out = []
    for g in gifts_list:
        sender = "anonymous"
        fid = getattr(g, "from_id", None)
        if fid is not None:
            try:
                su = await c.get_entity(fid)
                sender = getattr(su, "username", None) or (
                    (getattr(su, "first_name", "") or "") + " " + (getattr(su, "last_name", "") or "")
                ).strip() or "anonymous"
            except Exception:
                sender = "hidden"
        msg = getattr(g, "message", None)
        msg = getattr(msg, "text", msg) or ""
        out.append({
            "date": int(getattr(g, "date", 0) or 0),
            "sender": sender,
            "message": str(msg),
        })
    return {"username": username, "count": len(out), "gifts": out}
