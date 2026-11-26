package io.github.hyperprivacy.core.status

/**
 * Line-based wire format for [RuleReport] batches sent from hooked processes to the module app.
 * One report per line, tab-separated: `package  ruleId  STATE  hits`. Malformed lines are dropped
 * rather than failing the batch, because the payload crosses a trust boundary.
 */
public object StatusCodec {
    private const val FIELD_SEPARATOR = '\t'
    private const val LINE_SEPARATOR = '\n'
    private const val FIELD_COUNT = 4

    public fun encode(reports: Iterable<RuleReport>): String = reports.joinToString(LINE_SEPARATOR.toString()) { r ->
        require(listOf(r.packageName, r.ruleId).none { FIELD_SEPARATOR in it || LINE_SEPARATOR in it }) {
            "Report fields must not contain separators"
        }
        listOf(r.packageName, r.ruleId, r.state.name, r.hits.toString()).joinToString(FIELD_SEPARATOR.toString())
    }

    public fun decode(text: String?): List<RuleReport> = text.orEmpty().split(LINE_SEPARATOR).mapNotNull(::decodeLine)

    private fun decodeLine(line: String): RuleReport? {
        val fields = line.split(FIELD_SEPARATOR).takeIf { it.size == FIELD_COUNT } ?: return null
        val pkg = fields[0].ifEmpty { return null }
        val id = fields[1].ifEmpty { return null }
        val state = HookState.entries.firstOrNull { it.name == fields[2] } ?: return null
        val hits = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return RuleReport(pkg, id, state, hits)
    }
}
