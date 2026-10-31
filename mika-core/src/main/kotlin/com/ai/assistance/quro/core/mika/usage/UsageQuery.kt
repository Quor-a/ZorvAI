package com.ai.assistance.quro.core.mika.usage

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 看哪段时间。[Last24Hours] 是滚动的最近 24 小时(含此刻所在的小时),按小时分;
 * [LastDays] 是含今天在内的最近几个本地日历日,[Custom] 是本地日期 [from]..[to](含两头),都按天分,
 * 超过 [UsageQuery.MAX_DAILY_BARS] 天时按周分。
 */
sealed interface UsageRange {
    data object Last24Hours : UsageRange
    data class LastDays(val days: Int) : UsageRange
    data class Custom(val from: LocalDate, val to: LocalDate) : UsageRange
}

/** 趋势图一根柱子多长。 */
enum class UsageStep { Hour, Day, Week }

/** 一段时间里的合计。[tokens] 是输入加输出(缓存命中算在输入里,不另加)。 */
data class UsageTotals(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val calls: Long = 0,
    val callsWithoutUsage: Long = 0,
) {
    val tokens: Long get() = input + output

    operator fun plus(n: UsageCounts) =
        UsageTotals(input + n.input, output + n.output, cached + n.cached, calls + n.calls, callsWithoutUsage + n.callsWithoutUsage)
}

/**
 * 趋势图的一根:[start] 是这一格开始的本地时间(按小时时是整点,按天、按周时是第一天零点),
 * [lastDay] 是按周时这一格的最后一天(含;最后一格可能不满 7 天),其余为 null。
 * [byProvider] 是各服务商在这一格的 token(没有用量的不列)。
 */
data class UsageBucket(
    val start: LocalDateTime,
    val tokens: Long,
    val byProvider: Map<String, Long> = emptyMap(),
    val lastDay: LocalDate? = null,
)

/** 一个模型里某个用途的用量;[share] 是占这个模型的比例(0..1)。用途只作统计的一个维度,界面上不分。 */
data class PurposeUsage(val purpose: UsagePurpose, val totals: UsageTotals, val share: Double)

/** 一个服务商下一个模型的用量;[share] 是占这个服务商的比例。 */
data class ModelUsage(val model: String, val totals: UsageTotals, val share: Double, val purposes: List<PurposeUsage>)

/**
 * 一个服务商的用量;[share] 是占全部的比例。[name] 是现在的名字,服务商已删除([deleted])时是记账时留下的名字。
 * [order] 是这个服务商固定的先后(连着的按连接的先后,删掉的排在后面、按最早记账的时间),配色按它定,
 * 换时间范围也不变。
 */
data class ProviderUsage(
    val id: String,
    val name: String,
    val deleted: Boolean,
    val totals: UsageTotals,
    val share: Double,
    val models: List<ModelUsage>,
    val order: Int = 0,
)

/** 一段时间的统计:合计、趋势图([step] 一格多长)、按服务商 › 模型 › 用途的分项,各层按 token 从多到少。 */
data class UsageReport(
    val range: UsageRange,
    val step: UsageStep,
    val totals: UsageTotals,
    val series: List<UsageBucket>,
    val providers: List<ProviderUsage>,
) {
    val hourly: Boolean get() = step == UsageStep.Hour

    fun provider(id: String): ProviderUsage? = providers.firstOrNull { it.id == id }
}

/**
 * 从账本内容算统计。账本按 UTC 整点存,换成本地时间在这里做:一个整点桶按它开始的时刻落到本地的哪一天
 * (日期边界用 [LocalDate.atStartOfDay] 的规则,夏令时那天 23 或 25 小时也照样对);
 * 时区和 UTC 差半小时的地方,跨本地零点的那一个小时整个算到它开始的那天。
 */
object UsageQuery {
    private const val HOUR_MS = UsageLedger.HOUR_MS

    /** 按天分时最多几根;再长按周分(从第一天起每 7 天一根)。 */
    const val MAX_DAILY_BARS = 45

    /**
     * [live] 是现在还连着的服务商(id → 名字),按连接的先后排(迭代顺序就是先后);
     * 不在里面的算已删除,名字用账本留下的。
     */
    fun report(snapshot: UsageSnapshot, range: UsageRange, now: Instant, zone: ZoneId, live: Map<String, String>): UsageReport {
        val nowHour = Math.floorDiv(now.toEpochMilli(), HOUR_MS)
        val today = now.atZone(zone).toLocalDate()
        // 每一行落到第几根柱子;不在范围里为 null
        val slot: (Long) -> Int?
        val starts: List<LocalDateTime>
        val lastDays: List<LocalDate?>
        val step: UsageStep
        when (range) {
            UsageRange.Last24Hours -> {
                val first = nowHour - 23
                step = UsageStep.Hour
                starts = (0 until 24).map { i -> LocalDateTime.ofInstant(Instant.ofEpochMilli((first + i) * HOUR_MS), zone) }
                lastDays = starts.map { null }
                slot = { h -> (h - first).takeIf { it in 0..23 }?.toInt() }
            }
            is UsageRange.LastDays, is UsageRange.Custom -> {
                val (from, to) = days(range, today)
                val count = ChronoUnit.DAYS.between(from, to).toInt() + 1
                val days = if (count > MAX_DAILY_BARS) 7 else 1
                step = if (days == 7) UsageStep.Week else UsageStep.Day
                val bars = (count + days - 1) / days
                starts = (0 until bars).map { from.plusDays(it.toLong() * days).atStartOfDay() }
                lastDays = (0 until bars).map { i -> if (days == 1) null else minOf(from.plusDays(i * 7L + 6), to) }
                slot = { h ->
                    val d = Instant.ofEpochMilli(h * HOUR_MS).atZone(zone).toLocalDate()
                    if (d < from || d > to) null else ChronoUnit.DAYS.between(from, d).toInt() / days
                }
            }
        }

        val bars = LongArray(starts.size)
        val perBar = List(starts.size) { HashMap<String, Long>() }
        val byKey = HashMap<UsageKey, UsageTotals>()
        val firstSeen = HashMap<String, Long>()
        var total = UsageTotals()
        for (row in snapshot.rows) {
            firstSeen.merge(row.key.providerId, row.hour, ::minOf)
            val i = slot(row.hour) ?: continue
            val t = row.counts.input + row.counts.output
            bars[i] += t
            if (t > 0) perBar[i].merge(row.key.providerId, t, Long::plus)
            byKey[row.key] = (byKey[row.key] ?: UsageTotals()) + row.counts
            total += row.counts
        }

        // 固定先后:连着的按连接先后,删掉的排在后面、按最早记账的时间
        val liveOrder = live.keys.withIndex().associate { (i, id) -> id to i }
        val goneOrder = firstSeen.keys.filter { it !in live }.sortedWith(compareBy<String> { firstSeen.getValue(it) }.thenBy { it })
            .withIndex().associate { (i, id) -> id to live.size + i }

        val providers = byKey.entries.groupBy { it.key.providerId }.map { (pid, entries) ->
            val models = entries.groupBy { it.key.model }.map { (model, es) ->
                val mTotal = es.fold(UsageTotals()) { a, e -> a.add(e.value) }
                val purposes = es.groupBy { it.key.purpose }.map { (p, ps) -> p to ps.fold(UsageTotals()) { a, e -> a.add(e.value) } }
                    .sortedWith(compareByDescending<Pair<UsagePurpose, UsageTotals>> { it.second.tokens }
                        .thenByDescending { it.second.calls }.thenBy { it.first.ordinal })
                    .map { (p, t) -> PurposeUsage(p, t, share(t, mTotal)) }
                Triple(model, mTotal, purposes)
            }
            val pTotal = models.fold(UsageTotals()) { a, m -> a.add(m.second) }
            val sortedModels = models.sortedWith(compareByDescending<Triple<String, UsageTotals, List<PurposeUsage>>> { it.second.tokens }
                .thenByDescending { it.second.calls }.thenBy { it.first })
                .map { (m, t, ps) -> ModelUsage(m, t, share(t, pTotal), ps) }
            val name = live[pid] ?: snapshot.providerNames[pid] ?: pid
            ProviderUsage(
                pid, name, deleted = pid !in live, totals = pTotal, share = share(pTotal, total), models = sortedModels,
                order = liveOrder[pid] ?: goneOrder.getValue(pid),
            )
        }.sortedWith(compareByDescending<ProviderUsage> { it.totals.tokens }.thenByDescending { it.totals.calls }.thenBy { it.order })

        return UsageReport(
            range, step, totals = total,
            series = starts.indices.map { i -> UsageBucket(starts[i], bars[i], perBar[i], lastDays[i]) },
            providers = providers,
        )
    }

    /** 按天的范围落到哪几天(含两头);自定义的两头写反了就对调。 */
    fun days(range: UsageRange, today: LocalDate): Pair<LocalDate, LocalDate> = when (range) {
        is UsageRange.LastDays -> today.minusDays(range.days.coerceAtLeast(1) - 1L) to today
        is UsageRange.Custom -> if (range.from <= range.to) range.from to range.to else range.to to range.from
        UsageRange.Last24Hours -> today.minusDays(1) to today
    }

    private fun UsageTotals.add(o: UsageTotals) =
        UsageTotals(input + o.input, output + o.output, cached + o.cached, calls + o.calls, callsWithoutUsage + o.callsWithoutUsage)

    private fun share(part: UsageTotals, whole: UsageTotals): Double =
        if (whole.tokens <= 0) 0.0 else part.tokens.toDouble() / whole.tokens
}
