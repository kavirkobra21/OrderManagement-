"""Tests for telegram_notify.py against a local fake Telegram server (no network, no secrets)."""
import http.server
import json
import os
import shutil
import tempfile
import threading
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "github"))

# ---- fake Telegram Bot API (started before the module under test reads its environment)
REQUESTS = []
SCRIPT = {}
COUNTER = [100]


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        method = self.path.rsplit("/", 1)[-1]
        REQUESTS.append((method, body))
        queued = SCRIPT.get(method) or []
        if queued:
            status, payload = queued.pop(0)
        elif method.startswith("edit"):
            status, payload = 200, {"ok": True, "result": True}
        else:
            COUNTER[0] += 1
            status, payload = 200, {"ok": True, "result": {"message_id": COUNTER[0]}}
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


SERVER = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=SERVER.serve_forever, daemon=True).start()
os.environ["TELEGRAM_API_BASE"] = f"http://127.0.0.1:{SERVER.server_port}"
os.environ["RETRY_SLEEP_SCALE"] = "0"
os.environ["TELEGRAM_BOT_TOKEN"] = "TEST:TOKEN"
os.environ["TELEGRAM_CHAT_ID"] = "123"
os.environ.pop("GIT_COMMIT_STATE", None)

import telegram_notify as tn  # noqa: E402


def methods():
    return [m for m, _ in REQUESTS]


def order(**kw):
    o = {
        "schema": 1, "uid": "u1", "syncNo": 7, "invoiceNo": "۱", "dateText": "۱۴۰۵/۰۸/۱۸",
        "deliveryText": "سه شنبه", "description": "", "notes": "", "total": 110000, "deposit": 0,
        "items": [{"title": "طراحی لوگو", "qty": 1, "price": 100000}, {"title": "کاور", "qty": 2, "price": 5000}],
        "status": {"settled": True}, "attachments": [], "telegram": {"messageId": 0, "photoMessageId": 0, "imageHash": ""},
    }
    o.update(kw)
    return o


class Base(unittest.TestCase):
    def setUp(self):
        REQUESTS.clear()
        SCRIPT.clear()
        self.tmp = tempfile.mkdtemp()
        self.root = os.path.join(self.tmp, "orders")
        self.folder = os.path.join(self.root, "7")
        os.makedirs(os.path.join(self.folder, "images"))
        self.write_order(order())
        with open(os.path.join(self.folder, "customer.json"), "w", encoding="utf-8") as f:
            json.dump({"name": "میزو", "phone": "۰۹۶۴۶۴۹۴۹۴۹", "address": "مشهد"}, f, ensure_ascii=False)
        self.write_bytes("order_image.png", b"PNG-v1")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write_order(self, o):
        with open(os.path.join(self.folder, "order.json"), "w", encoding="utf-8") as f:
            json.dump(o, f, ensure_ascii=False)

    def write_bytes(self, rel, data):
        with open(os.path.join(self.folder, rel), "wb") as f:
            f.write(data)

    def run_main(self, *extra):
        return tn.main(["--orders", self.root, "--config", os.path.join(self.tmp, "none.json"), *extra])

    def state(self):
        with open(os.path.join(self.folder, "telegram.json"), encoding="utf-8") as f:
            return json.load(f)


class MessageFormat(unittest.TestCase):
    def test_persian_message(self):
        o = order(description="با احتیاط", notes="تحویل صبح")
        msg = tn.build_message(o, {"name": "میزو", "phone": "۰۹۶۴۶۴۹۴۹۴۹", "address": "مشهد", "contactId": "@mizo", "postalCode": "۱۲۳۴۵۶۷۸۹۰"}, True)
        self.assertIn("سفارش ثبت شد:", msg)
        self.assertIn("(شناسه سفارش): #7", msg)
        self.assertIn("(آیدی): @mizo", msg)
        self.assertIn("(کد پستی): 1234567890", msg)
        self.assertIn("(تعداد): 1", msg)
        self.assertIn("(شماره تماس): 09646494949", msg)          # English digits
        self.assertIn("- طراحی لوگو — 100,000 تومان", msg)        # price next to its item
        self.assertIn("- کاور ×2 — 10,000 تومان", msg)             # line total for qty 2
        self.assertIn("(جمع آیتم‌ها): 110,000 تومان", msg)
        self.assertIn("(هزینه کل): 110,000 تومان", msg)
        self.assertIn("(باقیمانده): 0 تومان", msg)
        self.assertIn("(وضعیت پرداخت): تسویه کامل", msg)
        self.assertIn("(وضعیت سفارش): ثبت شده", msg)

    def test_english_message(self):
        msg = tn.build_message(order(status={}), {"name": "A", "phone": "0912", "address": "X"}, False)
        self.assertIn("WORK ORDER NOTIFICATION", msg)
        self.assertIn("Order ID: #7", msg)
        self.assertIn("Remaining Balance: 110,000 Toman", msg)
        self.assertIn("Payment Status: Unpaid", msg)


class Delivery(Base):
    def test_first_run_sends_text_image_and_attachments(self):
        self.write_bytes("payment_receipt.jpg", b"JPG-1")
        self.write_bytes("images/order_x.jpg", b"JPG-2")
        self.write_order(order(attachments=[
            {"path": "payment_receipt.jpg", "type": "receipt"}, {"path": "images/order_x.jpg", "type": "order"}]))
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(methods(), ["sendMessage", "sendPhoto", "sendPhoto", "sendPhoto"])
        s = self.state()
        self.assertTrue(s["messageId"] and s["photoMessageId"])
        self.assertEqual(len(s["sentFiles"]), 2)

    def test_rerun_sends_nothing(self):
        self.assertEqual(self.run_main(), 0)
        REQUESTS.clear()
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(methods(), [])  # no duplicates when the Action runs again

    def test_changed_order_edits_same_message(self):
        self.run_main()
        mid = self.state()["messageId"]
        REQUESTS.clear()
        self.write_order(order(total=150000))
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(methods(), ["editMessageText"])
        self.assertIn(str(mid).encode(), REQUESTS[0][1])
        self.assertIn("150,000".encode(), REQUESTS[0][1])

    def test_changed_image_edits_photo(self):
        self.run_main()
        REQUESTS.clear()
        self.write_bytes("order_image.png", b"PNG-v2")
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(methods(), ["editMessageMedia"])

    def test_new_attachment_sent_once(self):
        self.run_main()
        REQUESTS.clear()
        self.write_bytes("images/p.jpg", b"JPG-3")
        self.write_order(order(attachments=[{"path": "images/p.jpg", "type": "product"}]))
        self.run_main()
        self.assertEqual(methods().count("sendPhoto"), 1)
        REQUESTS.clear()
        self.run_main()
        self.assertEqual(methods(), [])

    def test_retries_rate_limit_and_server_errors(self):
        SCRIPT["sendMessage"] = [
            (429, {"ok": False, "description": "Too Many Requests", "parameters": {"retry_after": 0}}),
            (502, {"ok": False, "description": "Bad Gateway"}),
        ]
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(methods().count("sendMessage"), 3)
        self.assertTrue(self.state()["messageId"])

    def test_permanent_error_keeps_state_for_next_run(self):
        SCRIPT["sendMessage"] = [(403, {"ok": False, "description": "Forbidden: bot can't initiate conversation"})]
        self.assertEqual(self.run_main(), 1)
        self.assertEqual(methods(), ["sendMessage"])       # not retried
        self.assertFalse(os.path.exists(os.path.join(self.folder, "telegram.json")))
        REQUESTS.clear()
        self.assertEqual(self.run_main(), 0)                # next run delivers it
        self.assertEqual(methods()[0], "sendMessage")

    def test_deleted_message_is_resent(self):
        self.run_main()
        REQUESTS.clear()
        SCRIPT["editMessageText"] = [(400, {"ok": False, "description": "Bad Request: message to edit not found"})]
        self.write_order(order(total=1))
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(methods(), ["editMessageText", "sendMessage"])

    def test_adopts_message_ids_from_old_in_app_sender(self):
        self.write_bytes("payment_receipt.jpg", b"JPG-1")
        self.write_order(order(
            attachments=[{"path": "payment_receipt.jpg", "type": "receipt"}],
            telegram={"messageId": 55, "photoMessageId": 56, "imageHash": tn.sha256_file(os.path.join(self.folder, "order_image.png"))}))
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(methods(), ["editMessageText"])    # edits message 55; no new message, image or receipt
        self.assertIn(b"55", REQUESTS[0][1])


class Config(Base):
    def test_disabled_in_config(self):
        cfg = os.path.join(self.tmp, "notify.json")
        json.dump({"enabled": False}, open(cfg, "w"))
        self.assertEqual(tn.main(["--orders", self.root, "--config", cfg]), 0)
        self.assertEqual(methods(), [])

    def test_missing_secrets_fails_clearly_only_when_there_is_work(self):
        saved = os.environ.pop("TELEGRAM_BOT_TOKEN")
        try:
            self.assertEqual(self.run_main(), 2)
            self.assertEqual(tn.main(["--orders", os.path.join(self.tmp, "empty"), "--config", "x"]), 0)
        finally:
            os.environ["TELEGRAM_BOT_TOKEN"] = saved

    def test_language_from_config(self):
        cfg = os.path.join(self.tmp, "notify.json")
        json.dump({"lang": "en"}, open(cfg, "w"))
        tn.main(["--orders", self.root, "--config", cfg])
        self.assertIn(b"WORK ORDER NOTIFICATION", REQUESTS[0][1])

    def test_test_mode(self):
        self.assertEqual(self.run_main("--test"), 0)
        self.assertEqual(methods(), ["sendMessage"])


if __name__ == "__main__":
    unittest.main()
