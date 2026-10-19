package dev.bettergit.core.git

object CommitMessagePolicy {
    private val conventional = Regex("^[a-z][a-z0-9-]*(\\([^\\r\\n)]+\\))?!?: .+")

    fun validate(message: String, subjectLimit: Int = 72): String? {
        val subject = message.lineSequence().firstOrNull().orEmpty()
        if (subject.isBlank()) return "Enter a commit subject."
        if (subject.length > subjectLimit) return "Keep the commit subject within $subjectLimit characters (currently ${subject.length})."
        if (!conventional.matches(subject) || subject.substringAfter(": ").isBlank() || subject.any(Char::isISOControl))
            return "Use a Conventional Commit subject, for example: feat(editor): add line annotations"
        return null
    }
}
