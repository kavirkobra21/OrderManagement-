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


class Deletion(Base):
    def test_trashed_customer_deletes_messages_and_sends_nothing(self):
        with open(os.path.join(self.folder, "customer.json"), "w", encoding="utf-8") as f:
            json.dump({"name": "x", "deletedAt": 123}, f)
        with open(os.path.join(self.folder, "telegram.json"), "w", encoding="utf-8") as f:
            json.dump({"messageId": 55, "photoMessageId": 56, "textHash": "h"}, f)
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(methods(), ["deleteMessage", "deleteMessage"])
        st = self.state()
        self.assertEqual((st["messageId"], st["photoMessageId"]), (0, 0))
        REQUESTS.clear()
        self.assertEqual(self.run_main(), 0)          # second run: nothing left to delete, nothing sent
        self.assertEqual(methods(), [])

    def test_restored_order_is_sent_again_as_new(self):
        with open(os.path.join(self.folder, "telegram.json"), "w", encoding="utf-8") as f:
            json.dump({"messageId": 0, "deleted": True}, f)
        self.assertEqual(self.run_main(), 0)
        self.assertIn("sendMessage", methods())
        self.assertNotIn("deleted", self.state())

    def test_tombstones_delete_messages_of_permanently_removed_orders(self):
        os.makedirs(os.path.join(self.tmp, "data"))
        json.dump({"messages": [901, 902]}, open(os.path.join(self.tmp, "data", "telegram_deleted.json"), "w"))
        SCRIPT["deleteMessage"] = [(400, {"ok": False, "error_code": 400, "description": "Bad Request: message to delete not found"})]
        self.assertEqual(self.run_main(), 0)          # already-gone message counts as done
        self.assertEqual(methods().count("deleteMessage"), 2)

    def test_summary_file_counts(self):
        self.assertEqual(self.run_main(), 0)
        st = json.load(open(os.path.join(self.tmp, "data", "telegram_status.json")))
        self.assertEqual((st["sent"] >= 1, st["failed"], st["errors"]), (True, 0, []))


class Config(Base):
    def test_enabled_flag_is_ignored_manual_sync_always_runs(self):
        cfg = os.path.join(self.tmp, "notify.json")
        json.dump({"enabled": False}, open(cfg, "w"))
        self.assertEqual(tn.main(["--orders", self.root, "--config", cfg]), 0)
        self.assertIn("sendMessage", methods())

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


class TemplateAndResend(unittest.TestCase):
    def setUp(self):
        REQUESTS.clear(); SCRIPT.clear()
        self.root = tempfile.mkdtemp()
        self.orders = os.path.join(self.root, "Order", "orders")
        self.folder = os.path.join(self.orders, "5")
        os.makedirs(self.folder)
        with open(os.path.join(self.folder, "order.json"), "w", encoding="utf-8") as f:
            json.dump(order(syncNo=5, description="🕯️ توضیح"), f, ensure_ascii=False)
        with open(os.path.join(self.folder, "customer.json"), "w", encoding="utf-8") as f:
            json.dump({"name": "علی 🌹", "phone": "0912"}, f, ensure_ascii=False)
        self.cfg = os.path.join(self.root, "notify.json")
        self.tg = tn.Telegram("T", "55")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def state(self):
        with open(os.path.join(self.folder, "telegram.json"), encoding="utf-8") as f:
            return json.load(f)

    def test_custom_template_keeps_emoji_and_drops_unknown(self):
        o = order(syncNo=5, description="🕯️ توضیح")
        msg = tn.message_for(o, {"name": "علی 🌹"}, True, "🧾 #{order_id} {customer_name}\n{items}\n{nope}\nجمع: {total_price}")
        self.assertIn("🧾 #5 علی 🌹", msg)
        self.assertNotIn("{", msg)
        self.assertIn("- طراحی لوگو — 100,000 تومان", msg)

    def test_empty_template_is_default_format(self):
        o = order(syncNo=5)
        self.assertEqual(tn.message_for(o, {"name": "A"}, True, ""), tn.build_message(o, {"name": "A"}, True))

    def test_resend_makes_a_new_message_and_stores_chat(self):
        self.assertTrue(tn.process_order(self.tg, self.folder, True, "5", "", False, "55"))
        first = self.state()
        self.assertEqual(first["chatId"], "55")
        n = methods().count("sendMessage")
        self.assertTrue(tn.process_order(self.tg, self.folder, True, "5", "", True, "55"))
        self.assertEqual(methods().count("sendMessage"), n + 1)          # NEW message, not an edit
        self.assertNotEqual(self.state()["messageId"], first["messageId"])

    def test_template_change_edits_same_message(self):
        tn.process_order(self.tg, self.folder, True, "5", "", False, "55")
        mid = self.state()["messageId"]
        tn.process_order(self.tg, self.folder, True, "5", "نسخه جدید {customer_name}", False, "55")
        self.assertIn("editMessageText", methods())
        self.assertEqual(self.state()["messageId"], mid)

    def test_main_resend_unknown_order_fails(self):
        with open(self.cfg, "w") as f:
            json.dump({"enabled": True}, f)
        self.assertEqual(tn.main(["--orders", self.orders, "--config", self.cfg, "--resend", "99"]), 1)

    def test_form_aliases_name_and_id(self):
        o = order(syncNo=5)
        msg = tn.render_template("{name} | {id} | {phone} | {postal_code} | {address} | #{order_id}", o,
                                 {"name": "محمدرضا", "contactId": "@Mmd", "phone": "0996", "postalCode": "89", "address": "یزد"}, True)
        self.assertEqual(msg, "محمدرضا | @Mmd | 0996 | 89 | یزد | #5")

    def test_discount_lines_and_remaining_use_final_price(self):
        o = order(syncNo=5, total=100000, discountPercent=10.0, discountFixed=5000, discount=15000, finalTotal=85000, deposit=20000, status={})
        msg = tn.build_message(o, {"name": "A"}, True)
        self.assertIn("(هزینه کل): 100,000 تومان", msg)
        self.assertIn("(تخفیف): 10% + 5,000 تومان = 15,000 تومان", msg)
        self.assertIn("(مبلغ نهایی): 85,000 تومان", msg)
        self.assertIn("(باقیمانده): 65,000 تومان", msg)
        v = tn.template_values(o, {"name": "A"}, True)
        self.assertEqual(v["final_price"], "85,000 تومان"); self.assertEqual(v["original_price"], "100,000 تومان")

    def test_no_discount_message_has_no_discount_lines(self):
        msg = tn.build_message(order(syncNo=5), {"name": "A"}, True)
        self.assertNotIn("تخفیف", msg); self.assertNotIn("مبلغ نهایی", msg)
