"""Full presentation-chain validation; identity allocation is not commit order."""

from common import PresentationSafetyError


def assert_audit_chain_rows(rows, state_count: int, tip_hash: str | None) -> dict[str, int]:
    """Rows are (id, prev_hash, row_hash, recomputed_hash, synthetic), from one snapshot."""
    if state_count != 1:
        raise PresentationSafetyError("Audit chain must have exactly one state row")
    by_hash = {}
    for row in rows:
        row_id, _, row_hash, recomputed, _ = row
        if row_hash is None or row_hash != recomputed or row_hash in by_hash:
            raise PresentationSafetyError(f"Audit hash chain contains an invalid hash at row {row_id}")
        by_hash[row_hash] = row
    visited = set()
    current = tip_hash
    while current is not None:
        if current in visited or current not in by_hash:
            raise PresentationSafetyError("Audit hash chain contains a cycle or missing predecessor/tip")
        visited.add(current)
        current = by_hash[current][1]
    if len(visited) != len(rows):
        raise PresentationSafetyError("Audit chain tip does not cover every stored row")
    return {"rows": len(rows), "broken": 0, "synthetic_unhashed": 0}
