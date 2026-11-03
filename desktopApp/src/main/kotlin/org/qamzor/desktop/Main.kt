package org.qamzor.desktop

import org.qamzor.core.ArchiveEngine
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

fun main() {
    SwingUtilities.invokeLater {
        val frame = JFrame("QAMZOR — Archive Manager")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.minimumSize = Dimension(620, 440)
        val title = JLabel("QAMZOR", SwingConstants.CENTER).apply { font = font.deriveFont(28f) }
        val subtitle = JLabel("Create, inspect, verify and extract QZRA archives", SwingConstants.CENTER)
        val log = JTextArea("QAMZOR Desktop 0.1.0-dev\nKotlin client: QZRA 1.0 reader / QZRA 1.1 writer\nEncrypted QZRA 1.2 is not yet supported by this client.\n")
            .apply { isEditable = false; lineWrap = true; wrapStyleWord = true; border = BorderFactory.createEmptyBorder(12, 12, 12, 12) }
        val buttons = JPanel(java.awt.GridLayout(2, 2, 12, 12)).apply { border = BorderFactory.createEmptyBorder(16, 24, 16, 24) }

        fun runTask(task: () -> String) {
            try { log.append("\n${task()}\n") }
            catch (error: Exception) { log.append("\nOperation failed: ${error.message}\n") }
        }

        buttons.add(JButton("Create archive").apply {
            addActionListener {
                runTask {
                    val chooser = JFileChooser().apply { isMultiSelectionEnabled = true; fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES }
                    check(chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) { "No source selected" }
                    val sources = chooser.selectedFiles.toList().ifEmpty { listOf(chooser.selectedFile) }
                    val save = JFileChooser().apply { selectedFile = File("archive.qzra") }
                    check(save.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) { "No destination selected" }
                    val result = ArchiveEngine.create(save.selectedFile, sources, overwrite = true)
                    "Created ${result.archive.name}: ${result.entries} entries, ${result.files} files, ${result.originalBytes} source bytes."
                }
            }
        })
        buttons.add(JButton("Inspect / verify").apply {
            addActionListener {
                runTask {
                    val chooser = archiveChooser()
                    check(chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) { "No archive selected" }
                    val archive = chooser.selectedFile
                    val entries = ArchiveEngine.list(archive)
                    val verified = ArchiveEngine.verify(archive)
                    "Verified ${archive.name} (${verified.version}): ${verified.filesChecked} files, ${verified.bytesChecked} bytes.\n" +
                        entries.take(40).joinToString("\n") { "${it.type}\t${it.size}\t${it.path}" }
                }
            }
        })
        buttons.add(JButton("Extract archive").apply {
            addActionListener {
                runTask {
                    val chooser = archiveChooser()
                    check(chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) { "No archive selected" }
                    val output = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                    check(output.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) { "No destination folder selected" }
                    val files = ArchiveEngine.extract(chooser.selectedFile, output.selectedFile)
                    "Extracted ${files.size} verified files to ${output.selectedFile.absolutePath}."
                }
            }
        })
        buttons.add(JButton("About / format support").apply {
            addActionListener { JOptionPane.showMessageDialog(frame, "QAMZOR Desktop 0.1.0-dev\nReads QZRA 1.0 and 1.1.\nWrites QZRA 1.1 with chunk-level deduplication.\nQZRA 1.2 encrypted archives, signing, recovery and repository management are not yet exposed in this desktop client.", "About QAMZOR", JOptionPane.INFORMATION_MESSAGE) }
        })

        val header = JPanel(BorderLayout()).apply {
            add(title, BorderLayout.CENTER)
            add(subtitle, BorderLayout.SOUTH)
            border = BorderFactory.createEmptyBorder(22, 12, 8, 12)
        }
        frame.layout = BorderLayout()
        frame.add(header, BorderLayout.NORTH)
        frame.add(JScrollPane(log), BorderLayout.CENTER)
        frame.add(buttons, BorderLayout.SOUTH)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}

private fun archiveChooser() = JFileChooser().apply {
    fileSelectionMode = JFileChooser.FILES_ONLY
    fileFilter = FileNameExtensionFilter("QAMZOR archives (*.qzra)", "qzra")
}
