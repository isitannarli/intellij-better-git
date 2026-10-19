package dev.bettergit.core.git

data class WorkingChange(
    val path: String,
    val oldPath: String? = null,
    val indexStatus: Char = '.',
    val workTreeStatus: Char = '.',
    val untracked: Boolean = false,
    val conflicted: Boolean = false,
    val submodule: Boolean = false,
) {
    val staged: Boolean get() = !untracked && !conflicted && indexStatus != '.'
    val unstaged: Boolean get() = untracked || conflicted || workTreeStatus != '.'
}

data class WorkingFileDiff(val path: String, val before: String, val after: String, val beforeLabel: String, val afterLabel: String)

internal object WorkingChangesParser {
    fun parse(output: String): List<WorkingChange> {
        val records = output.split('\u0000')
        val result = mutableListOf<WorkingChange>()
        var index = 0
        while (index < records.size) {
            val record = records[index++]
            if (record.startsWith("? ")) {
                result += WorkingChange(record.drop(2), untracked = true)
                continue
            }
            val count = when {
                record.startsWith("1 ") -> 9
                record.startsWith("2 ") -> 10
                record.startsWith("u ") -> 11
                else -> continue
            }
            val fields = record.split(' ', limit = count)
            require(fields.size == count && fields[1].length == 2) { "Unexpected Git status entry" }
            val oldPath = if (record.startsWith("2 ")) {
                require(index < records.size && records[index].isNotEmpty()) { "Missing rename origin" }
                records[index++]
            } else null
            result += WorkingChange(
                fields.last(), oldPath, fields[1][0], fields[1][1],
                conflicted = record.startsWith("u "), submodule = fields[2].startsWith('S'),
            )
        }
        return result
    }
}
