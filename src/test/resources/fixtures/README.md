# Test fixtures

Responses of `https://api.anthropic.com/api/oauth/usage`, copied from the
`java-aip` repository. No single account shows every shape at once.

| File | Shape |
| --- | --- |
| `usage-credits.json` | The **usage-based format**. Real response of a usage-based seat, amounts neutralised: plan windows all `null`, balance in `spend`. |
| `usage-windows.json` | The **seat-based format**, of which this made-up response is the only example the application was built on. Synthetic Pro/Max seat: `spend.enabled` false, windows populated. |
| `usage-empty.json` | Synthetic: neither spend nor windows. |

Each carries detail the parser must get right: placeholder keys the real
document ships, and an `extra_usage` object that also holds a `utilization`
and is no plan window, so that it alone does not make a response the seat-based
format; in `usage-windows.json`, a window at exactly `0` and one whose
`resets_at` is `null`, each of which is a plan window all the same.
