#!/usr/bin/env python3
"""Send / edit Telegram notifications for orders stored in orders/<n>/ (runs in GitHub Actions).

Secrets come from the environment (GitHub Secrets): BOT_TOKEN, CHAT_ID. Standard library only.

Per order folder this script keeps orders/<n>/telegram.json:
  messageId / textHash         the text message (edited in place when the order changes)
  photoMessageId / imageSha    the generated notebook image (edited when it changes)
  sentFiles {path: sha256}     receipts / extra photos, each sent exactly once
so re-running the workflow never sends the same thing twice.
"""
import argparse
import hashlib
import json
import mimetypes
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

API_BASE = os.environ.get("TELEGRAM_API_BASE", "https://api.telegram.org").rstrip("/")
RETRY_SCALE = float(os.environ.get("RETRY_SLEEP_SCALE", "1"))  # tests set 0
MAX_PHOTO_BYTES = 9_500_000
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp"}


# ------------------------------------------------------------------ formatting

_FA = "۰۱۲۳۴۵۶۷۸۹"
_AR = "٠١٢٣٤٥٦٧٨٩"


def to_latin(s):
    return "".join(
        str(_FA.index(c)) if c in _FA else str(_AR.index(c)) if c in _AR else c for c in str(s or "")
    )


def money(v, fa=True):
    n = int(v or 0)
    return f"{n:,} " + ("تومان" if fa else "Toman")


def pay_status(o, fa):
    st = o.get("status", {})
    if st.get("settled") or st.get("paid"):
        return "تسویه کامل" if fa else "Fully paid"
    if st.get("depositReceived"):
        return "بیعانه دریافت شده" if fa else "Deposit received"
    return "پرداخت نشده" if fa else "Unpaid"


def order_status(o, fa):
    st = o.get("status", {})
    if st.get("delivered"):
        return "تحویل داده شده" if fa else "Delivered"
    if st.get("ready"):
        return "آماده شده" if fa else "Ready"
    if st.get("inProgress"):
        return "در حال انجام" if fa else "In progress"
    return "ثبت شده" if fa else "Registered"


def item_lines(o, fa):
    lines = []
    for it in o.get("items", []):
        title = (it.get("title") or "").strip()
        qty = int(it.get("qty") or 1)
        price = int(it.get("price") or 0) * qty  # price is per unit; the line shows the line total
        if not title and not price:
            continue
        label = f"{title} ×{qty}" if qty > 1 else title
        lines.append(f"- {label} — {money(price, fa)}" if price else f"- {label}")
    return lines


def remaining(o):
    st = o.get("status", {})
    if st.get("settled") or st.get("paid"):
        return 0
    return max(int(o.get("total") or 0) - int(o.get("deposit") or 0), 0)


def build_message(o, c, fa=True):
    items = o.get("items", [])
    items_sum = sum(int(i.get("price") or 0) * int(i.get("qty") or 1) for i in items)
    lines = item_lines(o, fa)
    phone = to_latin(c.get("phone", ""))
    no = o.get("syncNo", "")
    out = []
    if fa:
        out += ["سفارش ثبت شد:", ""]
        out += [f"(شناسه سفارش): #{no}", f"(نام مشتری): {c.get('name', '')}", f"(شماره تماس): {phone}",
                f"(شماره فاکتور): {o.get('invoiceNo', '')}", f"(تاریخ): {o.get('dateText', '')}",
                f"(زمان تحویل): {o.get('deliveryText', '')}", f"(آدرس): {c.get('address', '')}"]
        if lines:
            out += ["", "(سفارش‌ها):"] + lines
        if (o.get("description") or "").strip():
            out += ["", "(توضیحات):", o["description"].strip()]
        if (o.get("notes") or "").strip():
            out += ["", "(نکات سفارش):", o["notes"].strip()]
        out += [""]
        if items_sum:
            out += [f"(جمع آیتم‌ها): {money(items_sum)}"]
        out += [f"(هزینه کل): {money(o.get('total'))}", f"(بیعانه): {money(o.get('deposit'))}",
                f"(باقیمانده): {money(remaining(o))}", "",
                f"(وضعیت پرداخت): {pay_status(o, True)}", f"(وضعیت سفارش): {order_status(o, True)}"]
    else:
        out += ["WORK ORDER NOTIFICATION", ""]
        out += [f"Order ID: #{no}", f"Customer Name: {c.get('name', '')}", f"Phone Number: {phone}",
                f"Invoice Number: {to_latin(o.get('invoiceNo', ''))}", f"Order Date: {o.get('dateText', '')}",
                f"Delivery Date: {o.get('deliveryText', '')}", f"Address: {c.get('address', '')}"]
        if lines:
            out += ["", "Items:"] + lines
        if (o.get("description") or "").strip():
            out += ["", "Details:", o["description"].strip()]
        if (o.get("notes") or "").strip():
            out += ["", "Notes:", o["notes"].strip()]
        out += [""]
        if items_sum:
            out += [f"Items Subtotal: {money(items_sum, False)}"]
        out += [f"Total Amount: {money(o.get('total'), False)}", f"Deposit: {money(o.get('deposit'), False)}",
                f"Remaining Balance: {money(remaining(o), False)}", "",
                f"Payment Status: {pay_status(o, False)}", f"Order Status: {order_status(o, False)}"]
    return "\n".join(out)[:4000]


ATT_LABEL = {
    "receipt": ("رسید پرداخت", "Payment receipt"),
    "order": ("عکس سفارش", "Order photo"),
    "product": ("محصول نهایی", "Final product"),
    "satisfaction": ("رضایت مشتری", "Customer feedback"),
}


# ------------------------------------------------------------------ Telegram client

class TelegramError(Exception):
    def __init__(self, code, desc, not_found=False):
        super().__init__(f"Telegram error {code}: {desc}")
        self.code, self.desc, self.not_found = code, desc, not_found


def _multipart(fields, file_field, path):
    boundary = "----" + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
    with open(path, "rb") as fh:
        data = fh.read()
    parts.append(
        (f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; '
         f'filename="{os.path.basename(path)}"\r\nContent-Type: {ctype}\r\n\r\n').encode() + data + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


class Telegram:
    def __init__(self, token, chat_id):
        self.token, self.chat = token, str(chat_id)

    def call(self, method, fields=None, file_field=None, path=None):
        """POST with retries: network errors / 5xx back off, 429 honours retry_after, other 4xx raise."""
        fields = dict(fields or {}, chat_id=self.chat)
        url = f"{API_BASE}/bot{self.token}/{method}"
        last = None
        for attempt in range(5):
            if path:
                body, ctype = _multipart(fields, file_field, path)
            else:
                body, ctype = json.dumps(fields).encode(), "application/json"
            req = urllib.request.Request(url, data=body, headers={"Content-Type": ctype}, method="POST")
            wait = min(2 ** attempt * 2, 30)
            try:
                with urllib.request.urlopen(req, timeout=90) as r:
                    data = json.loads(r.read().decode())
                    if data.get("ok"):
                        return data.get("result")
                    raise TelegramError(200, data.get("description", ""))
            except urllib.error.HTTPError as e:
                try:
                    data = json.loads(e.read().decode())
                except Exception:
                    data = {}
                desc = data.get("description", e.reason if hasattr(e, "reason") else "")
                if e.code == 429:
                    wait = min(int((data.get("parameters") or {}).get("retry_after", wait)), 60)
                    last = TelegramError(429, desc)
                elif e.code >= 500:
                    last = TelegramError(e.code, desc)
                else:
                    low = (desc or "").lower()
                    if "message is not modified" in low:
                        return None
                    nf = "message to edit not found" in low or "message_id_invalid" in low or "can't be edited" in low
                    raise TelegramError(e.code, desc, not_found=nf)
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
                last = TelegramError(0, f"network error: {type(e).__name__}")
            if attempt < 4:
                time.sleep(wait * RETRY_SCALE)
        raise last

    def send_text(self, text):
        return self.call("sendMessage", {"text": text})["message_id"]

    def edit_text(self, mid, text):
        self.call("editMessageText", {"message_id": mid, "text": text})

    def send_file(self, path, caption=None):
        big = os.path.getsize(path) > MAX_PHOTO_BYTES or os.path.splitext(path)[1].lower() not in IMAGE_EXT
        f = {"caption": caption[:1000]} if caption else {}
        if big:
            return self.call("sendDocument", f, "document", path)["message_id"]
        return self.call("sendPhoto", f, "photo", path)["message_id"]

    def edit_photo(self, mid, path):
        media = json.dumps({"type": "photo", "media": "attach://photo"})
        self.call("editMessageMedia", {"message_id": mid, "media": media}, "photo", path)


# ------------------------------------------------------------------ state + orders

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_json(path, default=None):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except FileNotFoundError:
        return default
    except json.JSONDecodeError:
        return default


def save_json(path, data):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")


def commit_state(path, msg):
    """Persist delivery state right away (Actions only) so a later crash can never cause a resend."""
    if os.environ.get("GIT_COMMIT_STATE") != "1":
        return
    run = lambda *a: subprocess.run(a, check=False, capture_output=True, text=True)
    run("git", "add", path)
    if run("git", "diff", "--cached", "--quiet").returncode == 0:
        return
    run("git", "commit", "-m", msg)
    for _ in range(4):
        run("git", "pull", "--rebase", "--autostash")
        if run("git", "push").returncode == 0:
            return
        time.sleep(3 * RETRY_SCALE)
    print("::warning::could not push delivery state; it is committed locally only")


def adopt_legacy(order, folder, existing):
    """Orders sent by the old in-app sender carry their message ids in order.json: keep editing those
    messages instead of sending duplicates (and do not resend files that were already delivered)."""
    tg = order.get("telegram") or {}
    if existing or not tg.get("messageId"):
        return existing or {}
    sent = {}
    for a in order.get("attachments", []):
        p = os.path.join(folder, a.get("path", ""))
        if os.path.isfile(p):
            sent[a["path"]] = sha256_file(p)
    return {"messageId": tg["messageId"], "textHash": "", "photoMessageId": tg.get("photoMessageId", 0),
            "imageSha": tg.get("imageHash", ""), "sentFiles": sent}


def process_order(tg, folder, fa, no):
    order = load_json(os.path.join(folder, "order.json"))
    cust = load_json(os.path.join(folder, "customer.json"))
    if not order or not cust:
        print(f"order {no}: incomplete (order.json / customer.json missing), skipped")
        return True
    state_path = os.path.join(folder, "telegram.json")
    state = adopt_legacy(order, folder, load_json(state_path))
    state.setdefault("sentFiles", {})
    before = json.dumps(state, sort_keys=True)
    ok = True
    try:
        # ---- text: send once, afterwards edit the same message
        text = build_message(order, cust, fa)
        h = hashlib.sha256(text.encode()).hexdigest()
        if state.get("messageId") and state.get("textHash") != h:
            try:
                tg.edit_text(state["messageId"], text)
                state["textHash"] = h
                print(f"order {no}: message edited")
            except TelegramError as e:
                if not e.not_found:
                    raise
                state["messageId"] = 0
        if not state.get("messageId"):
            state["messageId"] = tg.send_text(text)
            state["textHash"] = h
            print(f"order {no}: message sent")

        # ---- generated notebook image
        img = os.path.join(folder, "order_image.png")
        if os.path.isfile(img):
            sha = sha256_file(img)
            cap = (f"فاکتور {order.get('invoiceNo', '')} — سفارش #{order.get('syncNo', no)}" if fa
                   else f"Invoice {to_latin(order.get('invoiceNo', ''))} — order #{order.get('syncNo', no)}")
            if state.get("photoMessageId") and state.get("imageSha") != sha:
                try:
                    tg.edit_photo(state["photoMessageId"], img)
                    state["imageSha"] = sha
                    print(f"order {no}: image edited")
                except TelegramError as e:
                    if not e.not_found:
                        raise
                    state["photoMessageId"] = 0
            if not state.get("photoMessageId"):
                state["photoMessageId"] = tg.send_file(img, cap)
                state["imageSha"] = sha
                print(f"order {no}: image sent")

        # ---- receipts and every other photo of the order, each exactly once
        for a in order.get("attachments", []):
            rel = a.get("path", "")
            p = os.path.join(folder, rel)
            if not rel or not os.path.isfile(p):
                continue
            sha = sha256_file(p)
            if state["sentFiles"].get(rel) == sha:
                continue
            lab = ATT_LABEL.get(a.get("type"), ("عکس", "Photo"))[0 if fa else 1]
            tg.send_file(p, f"{lab} — {'فاکتور' if fa else 'invoice'} {order.get('invoiceNo', '')}")
            state["sentFiles"][rel] = sha
            print(f"order {no}: {rel} sent")
    except TelegramError as e:
        ok = False
        print(f"::error::order {no}: {e.desc or e}")
    finally:
        if json.dumps(state, sort_keys=True) != before:
            state["updatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            save_json(state_path, state)
            commit_state(state_path, f"Telegram: order #{no} delivery state")
    return ok


def order_dirs(root):
    if not os.path.isdir(root):
        return []
    names = [n for n in os.listdir(root) if n.isdigit() and os.path.isdir(os.path.join(root, n))]
    return sorted(names, key=int)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--orders", default="orders")
    ap.add_argument("--config", default="config/notify.json")
    ap.add_argument("--test", action="store_true", help="send a test message and exit")
    args = ap.parse_args(argv)

    cfg = load_json(args.config, {}) or {}
    if cfg.get("enabled") is False and not args.test:
        print("Telegram notifications are switched off in the app (config/notify.json); nothing to do.")
        return 0
    fa = cfg.get("lang", "fa") != "en"
    dirs = order_dirs(args.orders)
    if not dirs and not args.test:
        print("No orders yet; nothing to do.")
        return 0

    token, chat = os.environ.get("BOT_TOKEN", "").strip(), os.environ.get("CHAT_ID", "").strip()
    if not token or not chat:
        print("::error::GitHub Secrets BOT_TOKEN and CHAT_ID are not set "
              "(Settings → Secrets and variables → Actions → New repository secret).")
        return 2
    tg = Telegram(token, chat)

    if args.test:
        try:
            tg.send_text("✅ پیام آزمایشی از دفترچه سفارش (GitHub Actions)" if fa else "✅ Test message from Order Notebook (GitHub Actions)")
            print("test message sent")
            return 0
        except TelegramError as e:
            print(f"::error::{e.desc or e}")
            return 1

    failed = 0
    for n in dirs:
        if not process_order(tg, os.path.join(args.orders, n), fa, n):
            failed += 1
    print(f"done: {len(dirs) - failed} ok, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
