import random
import sys
from locust import HttpUser, task, between
import gevent

# Article IDs present in the seeded DB. Write-heavy task posts views against these.
# Fresh Postgres + seed.py creates articles starting at id=1.
ARTICLE_IDS = [1, 2, 3, 4, 5, 6, 7, 8]


def _safe_print(msg: str) -> None:
    """Windows cp1252 console can't encode Georgian error responses; don't crash the greenlet."""
    try:
        print(msg)
    except UnicodeEncodeError:
        sys.stdout.write(msg.encode("ascii", "replace").decode("ascii") + "\n")


class CallCenterOperator(HttpUser):
    # თითოეული ოპერატორი 5-დან 15 წამამდე ინტერვალით ასრულებს მოქმედებებს
    wait_time = between(5, 15)

    def on_start(self):
        """სისტემაში შესვლა ვირტუალური იუზერის გაშვებისთანავე."""
        self.token = None
        operator_num = random.randint(1, 300)
        email = f"test_operator_{operator_num}@magti.ge"
        password = f"MagtiTest{operator_num}!"

        response = self.client.post("/api/auth/login", json={
            "email": email,
            "password": password
        })
        if response.status_code != 200:
            _safe_print(f"Login failed: {response.text}")
            return

        data = response.json()
        self.token = data.get("access_token")
        
        # SSE კავშირის დამყარება (ცოცხალი რეჟიმის სიმულაცია)
        gevent.spawn(self.open_sse)

    def open_sse(self):
        with self.client.get(
            "/api/stream",
            catch_response=True,
            headers={"Authorization": f"Bearer {self.token}"},
            stream=True
        ) as response:
            if response.status_code == 200:
                response.success()
                # Consumer loop to keep the stream alive without blocking the user
                for line in response.iter_lines():
                    if not line:
                        break
            else:
                response.failure(f"SSE connection failed with {response.status_code}")

    @task(3)
    def global_search(self):
        if not self.token:
            return
        """ძიების იმიტაცია ძირითად თემებზე."""
        queries = [
            "IPTV", "router", "MikroTik", "port forwarding", "DHCP",
            "VLAN", "firewall", "bandwidth", "outage", "fiber",
            "ONT", "GPON", "speed test", "MyMagti", "call center",
            "ticket", "notification", "announcement", "blacklist", "DNS",
        ]
        q = random.choice(queries)
        self.client.get(f"/api/search/global?q={q}", headers={"Authorization": f"Bearer {self.token}"})

    @task(1)
    def open_dashboard(self):
        if not self.token:
            return
        """მთავარი გვერდის გახსნის იმიტაცია."""
        self.client.get("/api/news?limit=20", headers={"Authorization": f"Bearer {self.token}"})
        self.client.get("/api/compliance/my-readings", headers={"Authorization": f"Bearer {self.token}"})

    @task(2)
    def track_article_view(self):
        """Write-heavy: POST view → SELECT article + INSERT AuditLog + commit.
        Stresses SQLite's single-writer lock under concurrency."""
        if not self.token:
            return
        article_id = random.choice(ARTICLE_IDS)
        self.client.post(
            f"/api/articles/{article_id}/view",
            headers={"Authorization": f"Bearer {self.token}"},
            name="/api/articles/[id]/view",
        )