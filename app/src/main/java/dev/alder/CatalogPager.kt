package dev.alder

internal class CatalogPager<T, K>(
    pages: List<List<T>>,
    private val paged: List<Boolean>,
    private val key: (T) -> K,
    private val include: (T) -> Boolean,
    private val fetch: (source: Int, skip: Int) -> List<T>,
) {
    private val offsets = pages.map { it.size }.toIntArray()
    private val exhausted = BooleanArray(pages.size) { !paged[it] || pages[it].isEmpty() }
    private val seen = mutableSetOf<K>()
    private val items = pages.flatten().filter { include(it) && seen.add(key(it)) }.toMutableList()

    fun hasMore(from: Int) = from < items.size || exhausted.any { !it }

    fun slice(from: Int, count: Int): List<T> {
        if (from >= items.size) {
            for (source in exhausted.indices) {
                var skipped = 0
                while (!exhausted[source] && from >= items.size) {
                    val page = fetch(source, offsets[source])
                    offsets[source] += page.size
                    items += page.filter { include(it) && seen.add(key(it)) }
                    // shortcut: stop after three pages with no new titles, revisit if a provider legitimately overlaps more.
                    if (page.isEmpty() || ++skipped == 3 && from >= items.size) exhausted[source] = true
                }
                if (from < items.size) break
            }
        }
        return items.drop(from).take(count)
    }
}
