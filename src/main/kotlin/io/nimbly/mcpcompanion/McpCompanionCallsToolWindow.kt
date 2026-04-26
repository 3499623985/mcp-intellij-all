package io.nimbly.mcpcompanion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListCellRenderer

/**
 * Tool window listing the most recent MCP tool calls.
 *
 * - Each row shows: status icon (●/✓/✗), HH:MM:SS, tool name, parameters preview, duration.
 * - Calls from THIS plugin's tools render in normal color; calls from other plugins
 *   (built-in JetBrains toolsets, third-party MCP servers) render in gray.
 * - The bottom split panel previews the selected call's full pretty-printed JSON parameters.
 * - Double-click (or Enter) opens a modal dialog with all metadata + parameters + response.
 *
 * Backed by [McpCompanionSettings.getCallRecords] which is populated by
 * [McpCompanionToolCallListener] on every tool call.
 */
class McpCompanionCallsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpCompanionCallsPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        content.setDisposer { panel.dispose() }
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val TOOL_WINDOW_ID = "MCP Companion Monitoring"
    }
}

internal class McpCompanionCallsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<McpCompanionSettings.CallRecord>()

    // Read-only JSON viewers — proper IntelliJ Editor with syntax highlighting + code folding.
    private val parametersDocument: Document = EditorFactory.getInstance().createDocument("")
    private val responseDocument: Document = EditorFactory.getInstance().createDocument("")
    private val parametersEditor: Editor = createJsonViewer(parametersDocument, project)
    private val responseEditor: Editor = createJsonViewer(responseDocument, project)

    private val tabbedPane = com.intellij.ui.components.JBTabbedPane().apply {
        addTab("Parameters", parametersEditor.component)
        addTab("Response", responseEditor.component)
    }

    private val list = JBList(model).apply {
        cellRenderer = CallRecordRenderer()
        background = UIUtil.getListBackground()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openDetailsDialog()
            }
        })
        addListSelectionListener { updateDetailsPanel() }
    }

    private val refreshListener: () -> Unit = {
        ApplicationManager.getApplication().invokeLater(::refreshFromSettings)
    }

    init {
        background = UIUtil.getListBackground()
        val splitter = JBSplitter(true, "io.nimbly.mcpcompanion.calls.splitter", 0.55f).apply {
            firstComponent = JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                viewportBorder = JBUI.Borders.empty()
                viewport.background = UIUtil.getListBackground()
                background = UIUtil.getListBackground()
            }
            secondComponent = tabbedPane
            background = UIUtil.getListBackground()
        }
        add(splitter, BorderLayout.CENTER)
        preferredSize = Dimension(600, 400)
        refreshFromSettings()
        McpCompanionSettings.getInstance().addCallRecordListener(refreshListener)
    }

    fun dispose() {
        McpCompanionSettings.getInstance().removeCallRecordListener(refreshListener)
        EditorFactory.getInstance().releaseEditor(parametersEditor)
        EditorFactory.getInstance().releaseEditor(responseEditor)
    }

    private fun refreshFromSettings() {
        val rememberedCallId = list.selectedValue?.callId
        val records = McpCompanionSettings.getInstance().getCallRecords()
        model.clear()
        records.forEach { model.addElement(it) }
        // Try to keep the same call selected after a refresh.
        if (rememberedCallId != null) {
            val newIndex = records.indexOfFirst { it.callId == rememberedCallId }
            if (newIndex >= 0) list.selectedIndex = newIndex
        }
        updateDetailsPanel()
    }

    private fun updateDetailsPanel() {
        val r = list.selectedValue
        val params = r?.parametersJson ?: ""
        val response = if (r == null) "" else buildString {
            append(r.response ?: "(not captured — current IntelliJ MCP API does not expose tool return values via ToolCallListener)")
            if (r.errorMessage != null) {
                append("\n\n─── Error ───\n")
                append(r.errorMessage)
            }
        }
        setDocumentText(parametersDocument, params)
        setDocumentText(responseDocument, response)
    }

    private fun setDocumentText(doc: Document, text: String) {
        ApplicationManager.getApplication().runWriteAction {
            doc.setText(text)
        }
    }

    private fun createJsonViewer(doc: Document, project: Project): Editor {
        val factory = EditorFactory.getInstance()
        val editor = factory.createViewer(doc, project) as EditorEx
        // Use JSON syntax highlighting if the JSON file type is registered (it is in all IntelliJ-based IDEs).
        val jsonFileType = FileTypeManager.getInstance().findFileTypeByName("JSON") ?: PlainTextFileType.INSTANCE
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, jsonFileType)
        editor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isUseSoftWraps = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isCaretRowShown = false
            isRightMarginShown = false
            isIndentGuidesShown = false
        }
        // Hide the entire gutter component to drop the left margin completely.
        editor.gutterComponentEx.isVisible = false
        editor.setBorder(JBUI.Borders.empty())
        return editor
    }

    private fun openDetailsDialog() {
        val record = list.selectedValue ?: return
        CallDetailsDialog(project, record).show()
    }

}

private class CallRecordRenderer : JPanel(), ListCellRenderer<McpCompanionSettings.CallRecord> {

    private val timeFormat = SimpleDateFormat("HH:mm:ss")
    private val ourIcon = IconLoader.getIcon("/icons/mcpStatusIdle.svg", McpCompanionCallsPanel::class.java)
    // Transparent placeholder of the same size keeps row height consistent for non-our tools.
    private val emptyIcon = com.intellij.util.ui.EmptyIcon.create(ourIcon.iconWidth, ourIcon.iconHeight)

    private val statusTimeLabel = JLabel()
    private val iconLabel = JLabel()
    private val nameDurationLabel = JLabel()

    init {
        layout = GridBagLayout()
        border = JBUI.Borders.empty(4, 8)
        isOpaque = true
        // Column 1 — status + time (fixed width, predictable column).
        add(statusTimeLabel, GridBagConstraints().apply {
            gridx = 0; anchor = GridBagConstraints.WEST
            ipadx = JBUI.scale(4)
        })
        // Column 2 — our MCP icon (16 px, fixed). Empty placeholder for non-our tools.
        add(iconLabel, GridBagConstraints().apply {
            gridx = 1; anchor = GridBagConstraints.WEST
            insets = JBUI.insets(0, 6, 0, 6)
        })
        // Column 3 — tool name + duration (takes remaining space).
        add(nameDurationLabel, GridBagConstraints().apply {
            gridx = 2; anchor = GridBagConstraints.WEST
            weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
        })
    }

    override fun getListCellRendererComponent(
        list: JList<out McpCompanionSettings.CallRecord>,
        value: McpCompanionSettings.CallRecord,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val statusIcon = when (value.status) {
            McpCompanionSettings.CallRecord.Status.RUNNING -> "●"
            McpCompanionSettings.CallRecord.Status.SUCCESS -> "✓"
            McpCompanionSettings.CallRecord.Status.ERROR -> "✗"
        }
        val time = timeFormat.format(Date(value.startedAtMillis))
        val durationStr = value.durationMs?.let { formatMs(it) } ?: "running…"
        val ownership = if (value.isOwnTool) "" else " (other)"

        statusTimeLabel.text = "<html><nobr>$statusIcon&nbsp;<code>$time</code></nobr></html>"
        // Show our wireframe icon ONLY for our own tools — empty placeholder for others (keeps alignment).
        iconLabel.icon = if (value.isOwnTool) ourIcon else emptyIcon
        nameDurationLabel.text = "<html><nobr><b>${value.toolName}</b>${ownership}&nbsp;&mdash; $durationStr</nobr></html>"

        val bg = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
        val fg = when {
            isSelected -> UIUtil.getListSelectionForeground(true)
            !value.isOwnTool -> JBColor.GRAY
            value.status == McpCompanionSettings.CallRecord.Status.ERROR -> JBColor.RED
            else -> UIUtil.getListForeground()
        }
        background = bg
        statusTimeLabel.background = bg; statusTimeLabel.foreground = fg
        nameDurationLabel.background = bg; nameDurationLabel.foreground = fg
        statusTimeLabel.font = list.font
        nameDurationLabel.font = list.font
        return this
    }

    private fun formatMs(ms: Long): String = when {
        ms < 1_000 -> "${ms} ms"
        ms < 10_000 -> "%.1f s".format(ms / 1000.0)
        ms < 60_000 -> "${ms / 1000} s"
        else -> "${ms / 60_000} m ${(ms % 60_000) / 1000} s"
    }
}

private class CallDetailsDialog(
    project: Project,
    private val record: McpCompanionSettings.CallRecord,
) : DialogWrapper(project, true) {

    init {
        title = "MCP Call: ${record.toolName}"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val sb = StringBuilder()
        sb.appendLine("Tool: ${record.toolName}${if (record.isOwnTool) "" else "  (other plugin)"}")
        sb.appendLine("Call ID: ${record.callId}")
        sb.appendLine("Status: ${record.status}")
        sb.appendLine("Started: ${java.time.Instant.ofEpochMilli(record.startedAtMillis)}")
        record.durationMs?.let { sb.appendLine("Duration: $it ms") }
        record.client?.let { sb.appendLine("Client: $it") }
        record.errorMessage?.let { sb.appendLine("Error: $it") }
        sb.appendLine()
        sb.appendLine("─── Parameters (raw JSON) ───")
        sb.appendLine(record.parametersJson)
        sb.appendLine()
        sb.appendLine("─── Response ───")
        sb.appendLine(record.response ?: "(not captured — current IntelliJ MCP API does not expose tool return values via ToolCallListener)")

        val textArea = JTextArea(sb.toString()).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
            lineWrap = false
        }
        val scroll = JBScrollPane(textArea).apply {
            preferredSize = Dimension(700, 500)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        val panel = JPanel(BorderLayout())
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }
}
