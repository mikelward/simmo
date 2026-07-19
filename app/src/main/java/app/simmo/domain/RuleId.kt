package app.simmo.domain

import java.util.UUID

/**
 * Mints a stable rule id (see [CallingRule.id]). Rules get their id when they are
 * created — the preseeded defaults carry fixed ids, new rules mint one here —
 * so no rule is ever persisted without one and no load-time backfill is needed.
 */
fun newRuleId(): String = UUID.randomUUID().toString()
