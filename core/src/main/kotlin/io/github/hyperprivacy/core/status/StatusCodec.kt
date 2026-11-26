package io.github.hyperprivacy.core.status

/**
 * Line-based wire format for [RuleReport] batches sent from hooked processes to the module app.
 * One report per line, tab-separated: `package  ruleId  STATE  hits`. Malformed lines are dropped
 * rather than failing the batch, because the payload crosses a trust boundary.
 */
public object StatusCodec {
    private const val FIELD_COUNT = 4

    public fun encode(reports: Iterable<RuleReport>): String =
        TsvRows.encode(reports.map { listOf(it.packageName, it.ruleId, it.state.name, it.hits.toString()) })

    public fun decode(text: String?): List<RuleReport> = TsvRows.decode(text, FIELD_COUNT, ::parse)

    private fun parse(fields: List<String>): RuleReport? {
        val pkg = fields[0].ifEmpty { return null }
        val id = fields[1].ifEmpty { return null }
        val state = HookState.parse(fields[2]) ?: return null
        val hits = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return RuleReport(pkg, id, state, hits)
    }
}
