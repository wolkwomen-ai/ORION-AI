import os
import tempfile
import unittest

_tmp = tempfile.TemporaryDirectory()
os.environ["ARION_DB_PATH"] = os.path.join(_tmp.name, "test.sqlite3")
os.environ["ARION_API_TOKEN"] = "test-token"
os.environ["ARION_AI_API_KEY"] = ""

import app


class BackendFoundationTests(unittest.TestCase):
    def setUp(self):
        app.init_db()

    def test_database_tables_and_default_profile(self):
        with app.db() as conn:
            profile = conn.execute("SELECT * FROM profile WHERE id=1").fetchone()
            self.assertEqual(profile["active"], 0)
            self.assertEqual(profile["currency"], "UZS")
            names = {r["name"] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'")}
            self.assertTrue({"profile", "tasks", "ledger", "audit", "agent_state"}.issubset(names))

    def test_ledger_uses_integer_minor_units(self):
        with app.db() as conn:
            conn.execute("INSERT INTO ledger VALUES(?,?,?,?,?,?,?)",
                         ("test-id", "first sale", 12500, "UZS", "income", "", app.now()))
            row = conn.execute("SELECT amount_minor,direction FROM ledger WHERE id='test-id'").fetchone()
            self.assertEqual(row["amount_minor"], 12500)
            self.assertEqual(row["direction"], "income")

    def test_api_token_is_required(self):
        self.assertTrue(app.API_TOKEN)
        self.assertNotEqual(app.API_TOKEN, "")

    def test_no_unapproved_money_or_platform_executor(self):
        routes = {route.path for route in app.app.routes}
        self.assertNotIn("/v1/payments/transfer", routes)
        self.assertNotIn("/v1/platforms/send-message", routes)
        self.assertIn("/v1/agent/start", routes)


if __name__ == "__main__":
    unittest.main()
