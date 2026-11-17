#!/usr/bin/env python3
"""Write personal/assets/personal_defaults.json from a whisrs desktop configuration.

whisrs (https://github.com/y0sif/whisrs) is a desktop dictation daemon. Its config.toml already
holds everything a phone needs to dictate the same way: the transcription endpoint and model, the
cleanup LLM and its instruction, the vocabulary and the fixed replacements. This script turns it
into the asset PersonalDefaults.kt applies on the first start of the next build, so the phone and
the desktop stay configured from one file.

    python3 scripts/personal-defaults-from-whisrs.py [~/.config/whisrs/config.toml] [--style-title NAME]

--style-title names the cleanup style the app shows for the desktop instruction (default "whisrs").
--pref KEY=VALUE (repeatable) sets any other app preference by its key, e.g.
--pref single_tap_restore_and_record_enabled=true --pref ring_visibility_mode=KEYBOARD.

The output holds API keys and is gitignored. Its `version` is a hash of its content: the app
applies an asset once per version, so rebuilding without a config change keeps what was changed
in the app's Settings, and a config change is applied again over them.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import tomllib
from pathlib import Path
from urllib.parse import urlparse

REPO = Path(__file__).resolve().parent.parent
OUTPUT = REPO / "personal" / "assets" / "personal_defaults.json"

# How whisrs words the vocabulary for its cleanup LLM (`with_vocabulary` in
# src/daemon/pipeline.rs). {{vocabulary_list}} is filled in by the app with the current terms.
VOCABULARY_INSTRUCTION = (
    "Vocabulary: the speaker uses these names and terms. When the transcript holds something "
    "that sounds like one of them, write it exactly as listed. Do not add a term that was not "
    "spoken.\n{{vocabulary_list}}"
)

# whisrs sends its cleanup requests with this temperature and gives up after this long.
WHISRS_LLM_TEMPERATURE = 0.3
WHISRS_LLM_TIMEOUT_MS = 30_000


def base_url(url: str, suffix: str) -> str:
    url = url.strip().rstrip("/")
    return url[: -len(suffix)] if url.endswith(suffix) else url


def parse_pref(text: str):
    key, sep, value = text.partition("=")
    if not sep or not key.strip():
        raise argparse.ArgumentTypeError(f"expected KEY=VALUE, got {text!r}")
    value = value.strip()
    if value.lower() in ("true", "false"):
        return key.strip(), value.lower() == "true"
    try:
        return key.strip(), int(value)
    except ValueError:
        return key.strip(), value


def build(config: dict, style_title: str = "whisrs", prefs: dict | None = None) -> dict:
    general = config.get("general", {})
    out: dict = {"skipOnboarding": True}

    sidecar = config.get("asr-sidecar", {})
    if sidecar.get("url") and sidecar.get("api_key"):
        url = base_url(sidecar["url"], "/audio/transcriptions")
        entry = {"baseUrl": url, "model": sidecar.get("model", ""), "apiKey": sidecar["api_key"]}
        if (urlparse(url).hostname or "").lower() == "openrouter.ai":
            entry["preset"] = "openrouter"
        out["transcription"] = entry

    llm = config.get("llm", {})
    if general.get("llm_post_process") and llm.get("api_url") and llm.get("api_key"):
        extra: dict = {"temperature": WHISRS_LLM_TEMPERATURE}
        for key in ("reasoning_effort", "service_tier"):
            if str(llm.get(key, "")).strip():
                extra[key] = llm[key].strip()
        out["cleanup"] = {
            "baseUrl": base_url(llm["api_url"], "/chat/completions"),
            "model": llm.get("model", ""),
            "apiKey": llm["api_key"],
            "extraBody": extra,
            "timeoutMs": WHISRS_LLM_TIMEOUT_MS,
        }
        instruction = str(general.get("llm_instruction", "")).strip()
        if instruction:
            out["cleanupStyle"] = {
                "title": style_title,
                "subtitle": "The cleanup instruction of the desktop configuration",
                "prompt": f"{instruction}\n\n{VOCABULARY_INSTRUCTION}",
            }

    language = str(general.get("language", "")).strip()
    if language and language != "auto":
        out["language"] = language

    vocabulary = [t.strip() for t in general.get("vocabulary", []) if str(t).strip()]
    if vocabulary:
        out["vocabulary"] = vocabulary

    replacements = {k: v for k, v in config.get("replacements", {}).items() if k.strip() and str(v).strip()}
    if replacements:
        out["replacements"] = replacements
        out["replacementsBeforeCleanup"] = True

    # whisrs `[input] queue`: the next dictation can start while the previous one is processed.
    all_prefs = {"dictation_queue_enabled": True} if config.get("input", {}).get("queue") else {}
    all_prefs.update(prefs or {})
    if all_prefs:
        out["prefs"] = all_prefs

    digest = hashlib.sha256(json.dumps(out, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    return {"version": digest[:12], **out}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("config", nargs="?", default="~/.config/whisrs/config.toml")
    parser.add_argument("--style-title", default="whisrs")
    parser.add_argument("--pref", action="append", type=parse_pref, default=[], metavar="KEY=VALUE")
    args = parser.parse_args()
    with Path(args.config).expanduser().open("rb") as handle:
        defaults = build(tomllib.load(handle), args.style_title, dict(args.pref))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(defaults, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    OUTPUT.chmod(0o600)
    summary = {
        "version": defaults["version"],
        "transcription": defaults.get("transcription", {}).get("model"),
        "cleanup": defaults.get("cleanup", {}).get("model"),
        "language": defaults.get("language"),
        "vocabulary": len(defaults.get("vocabulary", [])),
        "replacements": len(defaults.get("replacements", {})),
    }
    print(f"wrote {OUTPUT}: {summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
