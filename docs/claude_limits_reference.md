# Claude Plan Limits — Implementation Reference

## Key Finding
Anthropic does **not** publish exact weekly token budgets. Weekly limits are shown as a % in
Claude Settings → Usage. The app uses this % + plan type to produce an *estimated* token figure.

---

## 5-Hour Session Window (all plans)
| Plan | Approx tokens / session | Approx messages / session |
|------|------------------------|--------------------------|
| Pro ($20/mo) | ~44,000 tokens | ~45 messages |
| Max 5× ($100/mo) | ~220,000 tokens | ~225 messages |
| Max 20× ($200/mo) | ~880,000 tokens | ~900 messages |

> Source: Anthropic Help Center + community benchmarks. Actual usage varies by message length/model.

---

## Weekly Usage (All Models bucket)
Anthropic introduced weekly limits in **August 2025**. They apply to fewer than 5% of users.
The limit is model-weighted — Opus 4 usage drains the weekly budget faster than Sonnet.

### Estimated weekly token budgets (derived from session limits × typical usage pattern)
| Plan | Estimated weekly budget | Notes |
|------|------------------------|-------|
| Pro ($20/mo) | ~300,000–600,000 tokens | Assumes 1–2 sessions/day |
| Max 5× ($100/mo) | ~1.5M–3M tokens | 5× Pro |
| Max 20× ($200/mo) | ~6M–12M tokens | 20× Pro |

> These are **estimates only**. Anthropic has not published exact weekly token caps.
> Use the user-reported weekly % as the source of truth, and back-calculate tokens from it.

---

## Token Estimation Formula (for analytics tile)
```
estimated_tokens_used = weekly_budget_midpoint × (weekly_pct / 100)
```

Where `weekly_budget_midpoint` per plan:
- Pro: 450,000 tokens
- Max 5×: 2,250,000 tokens
- Max 20×: 9,000,000 tokens

---

## Analytics Data — What the App Collects from User
Each analytics tile asks the user one question (from Claude Settings → Usage):

| Tile | User Input | Derived metric |
|------|-----------|---------------|
| Weekly Usage | "What % shows under All models?" | Bar chart, trend vs last week |
| Opus Usage | "What % shows under Claude Opus?" | Separate Opus drain tracker |
| Session health | "How many hours left?" (existing) | Countdown, % used |
| Token estimate | Plan type (one-time) + weekly % | ~tokens consumed this week |
| Weekly streak | Auto-tracked by app | Days with logged sessions |

---

## Plan Type — One-Time Setup
Ask during onboarding or first analytics view:
- Pro ($20/mo)
- Max 5× ($100/mo)
- Max 20× ($200/mo)
- I don't know / Skip

Store in SharedPreferences as `ss-plan`. Used only for token estimation — all other
analytics work without it.

---

## Important Caveats (show in UI)
- Token counts are estimates. Actual usage depends on message length and model choice.
- Opus 4 conversations use tokens ~3–5× faster than Sonnet for the same message count.
- Weekly budgets shown are midpoint estimates; Anthropic does not publish exact numbers.

---

## Sources
- [How do usage and length limits work? — Anthropic Help Center](https://support.claude.com/en/articles/11647753-how-do-usage-and-length-limits-work)
- [Claude Max Plan Explained — IntuitionLabs](https://intuitionlabs.ai/articles/claude-max-plan-pricing-usage-limits)
- [The Token Guide: How Claude's Limits Actually Work](https://limitededitionjonathan.substack.com/p/why-you-keep-hitting-claudes-usage)
- [Anthropic tweaks Claude usage limits — The Register (March 2026)](https://www.theregister.com/2026/03/26/anthropic_tweaks_usage_limits/)
