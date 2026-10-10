# Test fixtures

Responses of `https://api.anthropic.com/api/oauth/usage`, copied from the
`java-aip` repository.

| File | Shape |
| --- | --- |
| `usage-credits.json` | Real response of an account with spend, amounts neutralised: the keys beside `spend` all `null`, balance in `spend`. |
| `usage-empty.json` | Synthetic: no spend. |

Each carries detail the parser must get right: placeholder keys the real
document ships, and an `extra_usage` object that says the spend again.
