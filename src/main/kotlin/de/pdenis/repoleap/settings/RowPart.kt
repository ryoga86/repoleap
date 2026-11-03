package de.pdenis.repoleap.settings

import de.pdenis.repoleap.RepoLeapBundle
import org.jetbrains.annotations.Nls

/**
 * The parts of a popup row that follow the repository name; their order can be chosen in the settings.
 * Declaration order = default order.
 */
enum class RowPart(private val messageKey: String) {
    BRANCH("settings.show.branch"),
    TAGS("settings.show.tags"),
    PATH("settings.show.path"),
    WINDOW_STATE("settings.show.windowState");

    @get:Nls
    val displayName: String
        get() = RepoLeapBundle.message(messageKey)

    companion object {
        val DEFAULT_ORDER: List<RowPart> = entries.toList()

        /**
         * `"PATH,BRANCH"` -> `[PATH, BRANCH, TAGS, WINDOW_STATE]`: unknown and repeated names are ignored, parts that are
         * missing (e.g. added by a newer version) come last in their default order.
         */
        fun parse(text: String): List<RowPart> {
            val chosen = text.split(',', ' ', ';')
                .mapNotNull { name -> entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
                .distinct()
            return chosen + DEFAULT_ORDER.filterNot { it in chosen }
        }

        fun format(order: List<RowPart>): String = order.joinToString(",") { it.name }
    }
}
