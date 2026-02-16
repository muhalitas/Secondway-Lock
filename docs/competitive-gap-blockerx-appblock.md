# Competitive Gap: BlockerX vs AppBlock vs Secondway

Date: 2026-02-16

Sources:
- https://play.google.com/store/apps/details?id=io.funswitch.blocker
- https://play.google.com/store/apps/details?id=cz.mobilesoft.appblock
- https://www.blockerx.net
- https://www.appblock.app

## Observed Positioning

- BlockerX:
  - Explicitly targets porn recovery + accountability.
  - Promises uninstall alerts to accountability partners.
  - Emphasizes broad category blocking (adult, gambling, social, dating, gaming).
- AppBlock:
  - Positions as universal productivity blocker.
  - Strong focus on routines, schedules, and reducing screen time.
  - Presents high social proof and habit-oriented outcomes.
- Secondway (current):
  - Core mission is porn + digital gambling recovery with friction-first approach.
  - Browser hardening + app lock workflow exist, but packaging is less clear and advanced control depth is lower.

## Feature Matrix

| Capability | BlockerX | AppBlock | Secondway |
|---|---|---|---|
| Porn-specific messaging | Strong | Moderate | Strong |
| Gambling-specific messaging | Strong | Moderate | Strong |
| App blocking | Yes | Yes | Yes |
| Website/domain blocking | Yes | Yes | Partial (allowlist + safe browser mode) |
| Safe search enforcement | Unclear | Unclear | Yes |
| Time schedules/profiles | Limited/unclear | Strong | Limited |
| Strict anti-bypass UX | Moderate | Moderate | Basic |
| Accountability partner workflow | Strong | Weak | Missing |
| Cross-device sync | Likely | Likely | Basic (allowlist/settings sync) |
| Usage analytics/streaks | Moderate | Strong | Missing |
| Clear free/premium packaging | Stronger | Stronger | Basic |

## Main Gaps for Secondway

- Missing explicit accountability flow (partner, trusted contact, relapse alerts).
- Limited advanced blocking controls (schedules, profile presets, stricter mode layers).
- No clear progress/analytics loop (streak, saved time, relapse trend).
- Monetization packaging exists as draft but not yet implemented in product surfaces.
- Browser shell has improved, but still needs fewer simultaneous controls in high-frequency paths.

## Priority Actions

- P0:
  - Ship stricter anti-bypass UX layer (multi-step disable flow + cooldown).
  - Keep mission-first onboarding and profile presets (strict/balanced/focus).
  - Simplify primary shell interactions (fewer always-visible actions).
- P1:
  - Add accountability feature set (trusted contact + uninstall/deactivation event flow).
  - Add recurring schedules and trigger-based profile automation.
- P2:
  - Add progress analytics and habit loop (streak + recovery trend + weekly summary).
  - Launch production free/premium feature gates with transparent value.
