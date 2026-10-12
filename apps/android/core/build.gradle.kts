plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // archive/expo-rn:packages/shared runs vitest at TZ=UTC (vitest.config.ts); the ported
    // suite must see the same zone so the DST cases mean the same thing.
    systemProperty("user.timezone", "UTC")
    jvmArgs("-Duser.timezone=UTC")
}

kover {
    reports {
        // The list below mirrors archive/expo-rn:packages/shared/vitest.config.ts
        // `coverage.include`: add the ported class (e.g.
        // "com.taskrx.core.adherence.CalculatorKt") as each file is ported,
        // never the whole package. Kotlin has no per-file filter, so classes
        // are the unit of the gate. While the list is empty the rule below
        // has nothing to verify.
        val gatedClasses = listOf<String>(
            // e.g. "com.taskrx.core.time.LocalDayKt*",
            // domain/enums.ts, domain/reminder.ts, domain/schedule-times.ts: a Kotlin
            // file becomes a class per top-level type, so each file's classes are
            // listed (nested and companion classes ride on the `*`).
            "com.taskrx.core.domain.EnumsKt*",
            "com.taskrx.core.domain.ReminderKind*",
            "com.taskrx.core.domain.Priority*",
            "com.taskrx.core.domain.InstanceStatus*",
            "com.taskrx.core.domain.NotificationType*",
            "com.taskrx.core.domain.ReminderKt*",
            "com.taskrx.core.domain.Reminder",
            "com.taskrx.core.domain.ReminderInstance",
            "com.taskrx.core.domain.Caregiver",
            "com.taskrx.core.domain.ScheduleTimesKt*",
            "com.taskrx.core.domain.DailyTimes",
            "com.taskrx.core.domain.IntervalTimes",
            "com.taskrx.core.domain.WeeklyTimes",
            "com.taskrx.core.domain.OnceTimes",
            "com.taskrx.core.domain.NoneTimes",
            // time/local-day.ts
            "com.taskrx.core.time.LocalDayKt*",
            "com.taskrx.core.time.LocalDayBounds",
            // sync/conflict.ts
            "com.taskrx.core.sync.ConflictKt*",
            // sync/worker.ts retry policy, pull.ts badge, the Convex call shapes
            "com.taskrx.core.sync.PolicyKt*",
            "com.taskrx.core.sync.BadgeKt*",
            "com.taskrx.core.sync.SyncWorkerStatus",
            "com.taskrx.core.sync.SyncBadge",
            "com.taskrx.core.sync.ServerScheduleKt*",
            "com.taskrx.core.sync.PushKt*",
            "com.taskrx.core.sync.PushCall",
            "com.taskrx.core.sync.PushRejected",
            // backlog/completion.ts
            "com.taskrx.core.backlog.CompletionKt*",
            "com.taskrx.core.backlog.BacklogCompletion",
            // notifications/tier.ts
            "com.taskrx.core.notifications.TierKt*",
            "com.taskrx.core.notifications.Tier",
            // notifications/permission-state.ts
            "com.taskrx.core.notifications.PermissionStateKt*",
            "com.taskrx.core.notifications.PermissionStatus",
            "com.taskrx.core.notifications.PermissionSnapshotInput",
            "com.taskrx.core.notifications.PermissionSnapshot",
            "com.taskrx.core.notifications.PermissionRemedy",
            "com.taskrx.core.notifications.PermissionSeverity",
            "com.taskrx.core.notifications.PermissionGap",
            "com.taskrx.core.notifications.PermissionGateInput",
            // notifications: delivery table + ids, reschedule diff, tap routing, platform mapping
            "com.taskrx.core.notifications.DeliveryKt*",
            "com.taskrx.core.notifications.Mechanism",
            "com.taskrx.core.notifications.ChannelImportance",
            "com.taskrx.core.notifications.TierDelivery",
            "com.taskrx.core.notifications.NotificationSpec",
            "com.taskrx.core.notifications.RescheduleKt*",
            "com.taskrx.core.notifications.ArmedNotification",
            "com.taskrx.core.notifications.ReschedulePlan",
            "com.taskrx.core.notifications.RearmCause",
            "com.taskrx.core.notifications.TapKt*",
            "com.taskrx.core.notifications.TapTarget",
            "com.taskrx.core.notifications.TapOutcome*",
            "com.taskrx.core.notifications.PlatformPermissionKt*",
            // schedule/horizon.ts, schedule/occurrences.ts (types.ts is a record, not gated)
            "com.taskrx.core.schedule.HorizonKt*",
            "com.taskrx.core.schedule.OccurrencesKt*",
            // parser/draft.ts, extractors.ts, lexicon.ts, patterns.ts,
            // reminder-parser.ts, schedule-grammar.ts
            "com.taskrx.core.parser.DraftKt*",
            "com.taskrx.core.parser.ReminderDraft",
            "com.taskrx.core.parser.ReadPhraseOptions",
            "com.taskrx.core.parser.PhraseReading",
            "com.taskrx.core.parser.ExtractorsKt*",
            "com.taskrx.core.parser.LexiconKt*",
            "com.taskrx.core.parser.PatternsKt*",
            "com.taskrx.core.parser.Match",
            "com.taskrx.core.parser.ReminderParserKt*",
            "com.taskrx.core.parser.ParseReminderOptions",
            "com.taskrx.core.parser.ReminderParseResult",
            "com.taskrx.core.parser.ParserState",
            "com.taskrx.core.parser.ScheduleGrammarKt*",
            "com.taskrx.core.parser.ScheduleParseResult*",
            // adherence/calculator.ts, adherence/period.ts
            "com.taskrx.core.adherence.CalculatorKt*",
            "com.taskrx.core.adherence.DayAdherence",
            "com.taskrx.core.adherence.DayBucket",
            "com.taskrx.core.adherence.AdherenceStats",
            "com.taskrx.core.adherence.PeriodKt*",
            "com.taskrx.core.adherence.AdherencePeriod",
            // timeline/projection.ts
            "com.taskrx.core.timeline.ProjectionKt*",
            "com.taskrx.core.timeline.TimelineWindow",
            "com.taskrx.core.timeline.TimelineRow",
            "com.taskrx.core.timeline.ProjectTimelineInput",
            // refill/planner.ts, projection.ts, reconcile.ts
            "com.taskrx.core.refill.PlannerKt*",
            "com.taskrx.core.refill.PlanRefillOptions",
            "com.taskrx.core.refill.RefillDecision*",
            "com.taskrx.core.refill.ProjectionKt*",
            "com.taskrx.core.refill.UnprojectableReason*",
            "com.taskrx.core.refill.RefillProjection*",
            "com.taskrx.core.refill.SupplyKt*",
            "com.taskrx.core.refill.ReconcileKt*",
            "com.taskrx.core.refill.ReconcileRefillsInput",
            "com.taskrx.core.refill.RefillReconciliation",
            // sweep/planner.ts
            "com.taskrx.core.sweep.PlannerKt*",
            "com.taskrx.core.sweep.MissedVerdict",
            "com.taskrx.core.sweep.PlanMissedSweepInput",
        )

        filters {
            includes {
                // Kover treats an empty include list as "everything", so a
                // placeholder that matches nothing keeps the gate honest
                // until the first file is ported.
                classes(*(gatedClasses.ifEmpty { listOf("com.taskrx.core.__nothing_gated_yet__") }).toTypedArray())
            }
        }

        verify {
            rule {
                minBound(100, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                minBound(100, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}
