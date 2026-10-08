# Test fixtures

Responses of `https://api.anthropic.com/api/oauth/usage`, copied from the
`java-aip` repository. No single account shows every shape at once.

| File | Shape |
| --- | --- |
| `usage-credits.json` | Real response of a usage-based seat, amounts neutralised: plan windows all `null`, balance in `spend`. |
| `usage-windows.json` | Synthetic Pro/Max seat: `spend.enabled` false, windows populated. |
| `usage-empty.json` | Synthetic: neither spend nor windows. |

Each carries detail the parser must get right: placeholder keys the real
document ships, an `extra_usage` object that also holds a `utilization` and
must stay out of the window list, a window at exactly `0`, and one whose
`resets_at` is `null`.
