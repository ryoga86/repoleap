package de.pdenis.repoleap.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.git.HostRule
import de.pdenis.repoleap.git.HostingType
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/** Editable table "Host | Type | URL template" for the "Open Remote in Browser" rules. */
internal class RemoteRulesTable {

    private val hostColumn = object : ColumnInfo<HostRule, String>(RepoLeapBundle.message("settings.remotes.column.host")) {
        override fun valueOf(item: HostRule): String = item.host
        override fun isCellEditable(item: HostRule): Boolean = true
        override fun setValue(item: HostRule, value: String?) {
            item.host = value.orEmpty().trim()
        }
    }

    private val typeColumn = object : ColumnInfo<HostRule, HostingType>(RepoLeapBundle.message("settings.remotes.column.type")) {
        override fun valueOf(item: HostRule): HostingType = item.type
        override fun isCellEditable(item: HostRule): Boolean = true
        override fun setValue(item: HostRule, value: HostingType?) {
            if (value != null) item.type = value
        }

        override fun getEditor(item: HostRule): TableCellEditor =
            DefaultCellEditor(ComboBox(HostingType.entries.toTypedArray()).apply {
                renderer = object : SimpleListCellRenderer<HostingType>() {
                    override fun customize(list: JList<out HostingType>, value: HostingType?, index: Int, selected: Boolean, hasFocus: Boolean) {
                        text = value?.let(::displayName).orEmpty()
                    }
                }
            })

        override fun getRenderer(item: HostRule): TableCellRenderer = object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                text = (value as? HostingType)?.let(::displayName).orEmpty()
            }
        }

        override fun getPreferredStringValue(): String = displayName(HostingType.BITBUCKET_SERVER)
    }

    private val templateColumn = object : ColumnInfo<HostRule, String>(RepoLeapBundle.message("settings.remotes.column.template")) {
        override fun valueOf(item: HostRule): String = item.template
        override fun isCellEditable(item: HostRule): Boolean = item.type == HostingType.CUSTOM
        override fun setValue(item: HostRule, value: String?) {
            item.template = value.orEmpty().trim()
        }
    }

    val model = ListTableModel<HostRule>(hostColumn, typeColumn, templateColumn)
    private val table = TableView(model).apply {
        setShowGrid(false)
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        emptyText.text = RepoLeapBundle.message("settings.remotes.empty")
    }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            stopEditing()
            model.addRow(HostRule())
            val row = model.rowCount - 1
            table.selectionModel.setSelectionInterval(row, row)
            table.editCellAt(row, 0)
            table.editorComponent?.requestFocusInWindow()
        }
        .setPreferredSize(JBUI.size(500, 110))
        .createPanel()

    /** Rules as they should be stored: without empty hosts, as copies. */
    val rules: List<HostRule>
        get() {
            stopEditing()
            return model.items.filter { it.host.isNotBlank() }.map { it.copy(host = it.host.trim(), template = it.template.trim()) }
        }

    fun reset(rules: List<HostRule>) {
        stopEditing()
        model.items = rules.map { it.copy() }.toMutableList()
    }

    private fun stopEditing() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    companion object {
        fun displayName(type: HostingType): String = RepoLeapBundle.message("hosting.${type.name}")
    }
}
