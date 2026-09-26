"""The A06 campaign writes bookmarks, so it must refuse anything but a loopback test stack."""

from scripts.load.favorites_campaign import CONFIRMATION, refuse_target, summarize


def test_refuses_without_the_explicit_confirmation():
    assert refuse_target("http://localhost:4200", None)
    assert refuse_target("http://localhost:4200", "yes")


def test_refuses_the_demo_and_uat_stacks_and_anything_remote():
    assert "demo or UAT" in refuse_target("http://localhost:8081", CONFIRMATION)
    assert "demo or UAT" in refuse_target("http://127.0.0.1:8082", CONFIRMATION)
    assert "loopback" in refuse_target("https://portal.example.test", CONFIRMATION)


def test_accepts_a_loopback_test_stack():
    assert refuse_target("http://localhost:4200", CONFIRMATION) is None


def test_summary_counts_timeouts_and_flags_slow_calls_without_timing_them():
    calls = [
        {"cycle": 0, "shape": "single", "method": "POST", "status": 200, "ms": 20.0, "request_id": "a"},
        {"cycle": 0, "shape": "single", "method": "DELETE", "status": 204, "ms": 15.0, "request_id": "b"},
        {"cycle": 1, "shape": "double", "method": "POST", "status": 200, "ms": 1500.0, "request_id": "c"},
        {"cycle": 1, "shape": "double", "method": "POST", "status": "timeout", "ms": 5003.0, "request_id": None},
        {"cycle": 2, "shape": "toggle", "method": "POST", "status": 500, "ms": 30.0, "request_id": "d"},
    ]
    report = summarize(calls)
    assert report["requests"] == 5
    assert report["statuses"] == {"200": 2, "204": 1, "timeout": 1, "500": 1}
    assert report["latency_ms"]["POST"]["n"] == 3
    assert report["latency_ms"]["POST"]["max"] == 1500.0
    assert [c["request_id"] for c in report["flagged"]] == ["c", None, "d"]
