"""Scriptable ACP agent over stdio for Ferngeist end-to-end tests.

The gateway launches one process per chat. A prompt's text drives the turn:

    token=<word>   label for every chunk and the final marker (default "t")
    chunks=<n>     agent_message_chunk notifications to stream (default 1)
    every=<ms>     gap between chunks (default 0)

A turn streams "<token>#1 " .. "<token>#n " and ends with "<token>:done", so a
test can tell a complete transcript from one that lost or reordered chunks.
session/cancel stops the stream and ends the turn as cancelled.

Every frame in and out goes to --log-dir/<pid>.jsonl, timestamped, so the host
side can check when chunks were actually written.

Sessions live in --state-dir/<id>.json, so any process can list, load and resume the
sessions of every other one, as a real agent does with its on-disk history.
"""

import argparse
import itertools
import json
import os
import re
import sys
import threading
import time

PROTOCOL_VERSION = 1

out_lock = threading.Lock()
log_file = None
session_seq = itertools.count(1)
state_dir = None
# sessionId -> Event that stops that session's running turn
cancels = {}


def log(direction, frame):
    if log_file:
        log_file.write(json.dumps({"t": round(time.time(), 3), "dir": direction, "frame": frame}) + "\n")
        log_file.flush()


def send(frame):
    frame["jsonrpc"] = "2.0"
    with out_lock:
        sys.stdout.write(json.dumps(frame) + "\n")
        sys.stdout.flush()
        log("out", frame)


def result(req, value):
    send({"id": req["id"], "result": value})


def error(req, code, message):
    send({"id": req["id"], "error": {"code": code, "message": message}})


def update(session_id, kind, text):
    send({
        "method": "session/update",
        "params": {
            "sessionId": session_id,
            "update": {"sessionUpdate": kind, "content": {"type": "text", "text": text}},
        },
    })


def load_session(session_id):
    try:
        with open(os.path.join(state_dir, f"{session_id}.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, TypeError, ValueError):
        return None


def save_session(session_id, session):
    path = os.path.join(state_dir, f"{session_id}.json")
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(session, f)
    os.replace(path + ".tmp", path)


def all_sessions():
    return {name[:-5]: load_session(name[:-5]) for name in sorted(os.listdir(state_dir)) if name.endswith(".json")}


def directive(text, name, default):
    match = re.search(rf"\b{name}=(\w+)", text)
    return match.group(1) if match else default


def run_turn(req, session):
    params = req["params"]
    session_id = params["sessionId"]
    text = " ".join(b.get("text", "") for b in params.get("prompt", []) if b.get("type") == "text")
    session["history"].append(("user_message_chunk", text))
    save_session(session_id, session)
    token = directive(text, "token", "t")
    chunks = int(directive(text, "chunks", "1"))
    every = int(directive(text, "every", "0")) / 1000
    cancel = cancels[session_id] = threading.Event()

    stop_reason = "end_turn"
    for i in range(1, chunks + 1):
        if i > 1 and cancel.wait(every):
            stop_reason = "cancelled"
            break
        chunk = f"{token}#{i} "
        session["history"].append(("agent_message_chunk", chunk))
        save_session(session_id, session)
        update(session_id, "agent_message_chunk", chunk)
    if stop_reason == "end_turn":
        session["history"].append(("agent_message_chunk", f"{token}:done"))
        save_session(session_id, session)
        update(session_id, "agent_message_chunk", f"{token}:done")
    cancels.pop(session_id, None)
    result(req, {"stopReason": stop_reason})


def handle(req):
    method = req.get("method")
    params = req.get("params") or {}
    is_request = "id" in req

    if method == "initialize":
        result(req, {
            "protocolVersion": PROTOCOL_VERSION,
            "agentCapabilities": {
                "loadSession": True,
                "promptCapabilities": {"image": False, "audio": False, "embeddedContext": False},
                "sessionCapabilities": {"list": {}, "resume": {}, "close": {}},
            },
            "agentInfo": {"name": "ferngeist-e2e-mock", "title": "E2E Mock Agent", "version": "1.0.0"},
            "authMethods": [],
        })
    elif method == "authenticate":
        result(req, {})
    elif method == "session/new":
        # The gateway starts one process per chat, so the pid keeps ids unique across chats.
        session_id = f"e2e_{os.getpid()}_{next(session_seq)}"
        save_session(session_id, {"cwd": params.get("cwd", "/"), "title": session_id, "history": []})
        result(req, {"sessionId": session_id})
    elif method == "session/list":
        result(req, {"sessions": [
            {"sessionId": sid, "cwd": s["cwd"], "title": s["title"]} for sid, s in all_sessions().items() if s
        ]})
    elif method in ("session/load", "session/resume"):
        session = load_session(params.get("sessionId"))
        if session is None:
            error(req, -32002, "unknown session")
            return
        result(req, None)
        if method == "session/load":
            # ACP: answer the load first, then replay the conversation.
            for kind, text in session["history"]:
                update(params["sessionId"], kind, text)
    elif method == "session/prompt":
        session = load_session(params.get("sessionId"))
        if session is None:
            error(req, -32002, "unknown session")
            return
        # Off the read loop, so a cancel can arrive while chunks stream.
        threading.Thread(target=run_turn, args=(req, session), daemon=True).start()
    elif method == "session/cancel":
        cancel = cancels.get(params.get("sessionId"))
        if cancel:
            cancel.set()
        if is_request:
            result(req, None)
    elif method in ("session/set_mode", "session/set_model"):
        result(req, None)
    elif method == "session/set_config_option":
        result(req, {"configOptions": []})
    elif method == "session/close":
        result(req, None)
    elif is_request:
        error(req, -32601, f"Method not found: {method}")


def main():
    global log_file, state_dir
    parser = argparse.ArgumentParser()
    parser.add_argument("--log-dir")
    parser.add_argument("--state-dir", required=True)
    args = parser.parse_args()
    state_dir = args.state_dir
    os.makedirs(state_dir, exist_ok=True)
    if args.log_dir:
        os.makedirs(args.log_dir, exist_ok=True)
        log_file = open(os.path.join(args.log_dir, f"{os.getpid()}.jsonl"), "a", encoding="utf-8")
    for line in sys.stdin:
        if not line.strip():
            continue
        req = json.loads(line)
        log("in", req)
        handle(req)


if __name__ == "__main__":
    main()
