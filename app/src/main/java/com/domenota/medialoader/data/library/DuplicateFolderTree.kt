package com.domenota.medialoader.data.library

/** A directory is inferred only from an actual path/volume, never a display name or bucket hash. */
internal enum class DuplicateFolderSort { FILE_COUNT, NAME, SIZE }

internal data class DuplicateFolderNode(
    val id: String,
    val name: String,
    val location: String,
    val ownKeys: Set<String>,
    val allKeys: Set<String>,
    val children: List<DuplicateFolderNode>,
    val fileCount: Int,
    val sizeBytes: Long?,
    val folderCount: Int,
)

internal data class DuplicateFolderRow(val node: DuplicateFolderNode, val depth: Int)

internal object DuplicateFolderTree {
    private class MutableDirectory(
        val id: String,
        val name: String,
        val location: String,
    ) {
        val own = mutableListOf<DuplicateFolderOption>()
        val children = linkedMapOf<String, MutableDirectory>()
        fun freeze(): DuplicateFolderNode {
            val nested = children.values.map { it.freeze() }
            val options = own.toList()
            val keys = options.map { it.key }.toSet() + nested.flatMap { it.allKeys }
            val sizes = options.map { it.sizeBytes } + nested.map { it.sizeBytes }
            return DuplicateFolderNode(
                id = id,
                name = name,
                location = location,
                ownKeys = options.map { it.key }.toSet(),
                allKeys = keys,
                children = nested,
                fileCount = options.sumOf { it.fileCount } + nested.sumOf { it.fileCount },
                sizeBytes = if (sizes.any { it == null }) null else sizes.sumOf { it ?: 0L },
                folderCount = options.size + nested.sumOf { it.folderCount },
            )
        }
    }

    fun build(options: List<DuplicateFolderOption>): List<DuplicateFolderNode> {
        val roots = linkedMapOf<String, MutableDirectory>()
        // One folder identity appears once; prevents accidental duplicate counters.
        options.distinctBy { it.key }.forEach { folder ->
            val directory = folder.directoryId
            val separator = directory?.indexOf('|') ?: -1
            val namespace = if (separator > 0) directory!!.substring(0, separator) else ""
            val rawPath = if (separator > 0) directory!!.substring(separator + 1) else ""
            val parts = rawPath.split('/').filter { it.isNotBlank() }
            val safePath = namespace.isNotBlank() && parts.isNotEmpty() &&
                parts.none { it == "." || it == ".." || it.contains('\\') }
            if (!safePath) {
                val unknown = MutableDirectory("folder:${folder.key}", folder.name, folder.location)
                unknown.own.add(folder)
                roots[unknown.id] = unknown
            } else {
                var siblings = roots
                parts.forEachIndexed { index, part ->
                    val prefix = parts.take(index + 1).joinToString("/")
                    val id = "$namespace|$prefix"
                    val node = siblings.getOrPut(id) {
                        MutableDirectory(id, part, if (index == parts.lastIndex) folder.location else prefix)
                    }
                    if (index == parts.lastIndex) node.own.add(folder)
                    siblings = node.children
                }
            }
        }
        return roots.values.map { it.freeze() }
    }

    /** Stable order within each parent. Unknown sizes stay at the end in size mode. */
    fun sort(
        roots: List<DuplicateFolderNode>,
        mode: DuplicateFolderSort,
        descending: Boolean,
    ): List<DuplicateFolderNode> {
        val order = Comparator<DuplicateFolderNode> { first, second ->
            val main = when (mode) {
                DuplicateFolderSort.NAME -> first.name.compareTo(second.name, ignoreCase = true)
                DuplicateFolderSort.FILE_COUNT -> first.fileCount.compareTo(second.fileCount)
                DuplicateFolderSort.SIZE -> when {
                    first.sizeBytes == null && second.sizeBytes != null -> 1
                    first.sizeBytes != null && second.sizeBytes == null -> -1
                    else -> (first.sizeBytes ?: 0L).compareTo(second.sizeBytes ?: 0L)
                }
            }
            // Missing sizes always last, including in descending size order.
            val adjusted = if (mode == DuplicateFolderSort.SIZE &&
                (first.sizeBytes == null || second.sizeBytes == null)) main
                else if (descending) -main else main
            if (adjusted != 0) adjusted else first.name.compareTo(second.name, ignoreCase = true)
                .takeIf { it != 0 } ?: first.id.compareTo(second.id)
        }
        fun recursive(nodes: List<DuplicateFolderNode>): List<DuplicateFolderNode> =
            nodes.sortedWith(order).map { it.copy(children = recursive(it.children)) }
        return recursive(roots)
    }

    fun visible(
        roots: List<DuplicateFolderNode>,
        expanded: Set<String>,
        search: String,
    ): List<DuplicateFolderRow> {
        val query = search.trim()
        fun ownMatch(node: DuplicateFolderNode): Boolean =
            node.name.contains(query, ignoreCase = true) ||
                node.location.contains(query, ignoreCase = true)
        fun branchMatches(node: DuplicateFolderNode): Boolean =
            ownMatch(node) || node.children.any { branchMatches(it) }
        val rows = mutableListOf<DuplicateFolderRow>()
        fun visit(node: DuplicateFolderNode, depth: Int, ancestorMatch: Boolean) {
            val matches = query.isBlank() || ancestorMatch || branchMatches(node)
            if (!matches) return
            rows.add(DuplicateFolderRow(node, depth))
            if (node.id in expanded || query.isNotBlank()) {
                val passing = ancestorMatch || (query.isNotBlank() && ownMatch(node))
                node.children.forEach { visit(it, depth + 1, passing) }
            }
        }
        roots.forEach { visit(it, 0, false) }
        return rows
    }

    /** Search applies to matched subtrees, not every sibling under an expanded ancestor. */
    fun matchingKeys(roots: List<DuplicateFolderNode>, search: String): Set<String> {
        val query = search.trim()
        if (query.isBlank()) return roots.flatMap { it.allKeys }.toSet()
        fun collect(node: DuplicateFolderNode): Set<String> =
            if (node.name.contains(query, ignoreCase = true) ||
                node.location.contains(query, ignoreCase = true)) node.allKeys
            else node.children.flatMap { collect(it) }.toSet()
        return roots.flatMap { collect(it) }.toSet()
    }
}
