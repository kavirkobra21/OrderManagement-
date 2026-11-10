#!/usr/bin/env python3
"""Registers the Order app in the Kavir Store catalog (idempotent) and asks the store to sync.

Runs only inside GitHub Actions. Env:
  KAVIR_STORE_TOKEN  fine-grained token (Secret) - never printed
  STORE_REPO         e.g. kavirkobra19/KavirStore
  APP_REPO           PUBLIC repo that hosts the APK releases, e.g. kavirkobra19/OrderNotebook-Releases
Never touches order data; only catalog/sources.json of the store is edited.
"""
import base64, json, os, sys, time, urllib.error, urllib.request

API = "https://api.github.com"
ENTRY_DEFAULTS = {
    "enabled": True, "featured": False, "category": "productivity",
    "name": "Order Notebook", "name_fa": "دفترچه سفارش",
    "description": "Offline-first order notebook: customers, items with prices, notebook-style order image and GitHub backup.",
    "description_fa": "دفترچه سفارش با پشتیبانی آفلاین: مشتری‌ها، آیتم‌ها با قیمت، تصویر دفترچه‌ای سفارش و پشتیبان‌گیری در گیت‌هاب.",
    "icon": None, "screenshots": [], "asset_pattern": "^OrderNotebook-[0-9].*\\.apk$", "prerelease": False,
}


def call(method, path, token, body=None):
    req = urllib.request.Request(API + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                                          "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "order-notebook-ci"})
    for i in range(4):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            if e.code >= 500 and i < 3:
                time.sleep(2 * (i + 1)); continue
            raise
    return {}


def merge_entry(sources: dict, repo: str):
    """Returns (new_sources, changed). Never duplicates an existing repo and never overwrites admin edits."""
    apps = sources.setdefault("apps", [])
    if any(str(a.get("repo", "")).lower() == repo.lower() for a in apps):
        return sources, False
    apps.append({"repo": repo, **ENTRY_DEFAULTS})
    return sources, True


def main():
    token = os.environ.get("KAVIR_STORE_TOKEN", "")
    store, app = os.environ.get("STORE_REPO", ""), os.environ.get("APP_REPO", "")
    if not token:
        print("::error::Secret KAVIR_STORE_TOKEN is not set"); return 2
    if not store or not app:
        print("::error::STORE_REPO / APP_REPO missing"); return 2
    try:
        info = call("GET", f"/repos/{app}", token)
        if info.get("private"):
            print(f"::error::{app} is private. The store needs a PUBLIC repo for APK releases."); return 2
        for attempt in range(3):
            f = call("GET", f"/repos/{store}/contents/catalog/sources.json", token)
            sources = json.loads(base64.b64decode(f["content"]).decode("utf-8"))
            sources, changed = merge_entry(sources, app)
            if not changed:
                print("already registered - catalog record is reused, nothing duplicated"); break
            text = json.dumps(sources, indent=2, ensure_ascii=False) + "\n"
            try:
                call("PUT", f"/repos/{store}/contents/catalog/sources.json", token, {
                    "message": "chore(catalog): register Order Notebook", "sha": f["sha"],
                    "content": base64.b64encode(text.encode()).decode()})
                print("registered in catalog/sources.json"); break
            except urllib.error.HTTPError as e:
                if e.code in (409, 422) and attempt < 2:
                    time.sleep(3); continue
                raise
        call("POST", f"/repos/{store}/dispatches", token,
             {"event_type": "app-release", "client_payload": {"repo": app}})
        print("store sync requested")
        return 0
    except urllib.error.HTTPError as e:
        hint = {401: "token invalid/expired", 403: "token lacks Contents: Read and write on the store repo",
                404: "repo not found or token has no access to it"}.get(e.code, "")
        print(f"::error::GitHub API {e.code} {hint}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
