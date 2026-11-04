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
        val subtitle = JLabel("Convert files and folders to QZRA, browse, verify and extract", SwingConstants.CENTER)
        val log = JTextArea("QAMZOR Desktop 0.9.0\nConvert files/folders losslessly into the native .qzra container.\nThe original source files remain unchanged.\n")
            .apply { isEditable = false; lineWrap = true; wrapStyleWord = true; border = BorderFactory.createEmptyBorder(12, 12, 12, 12) }
        val buttons = JPanel(java.awt.GridLayout(2, 2, 12, 12)).apply { border = BorderFactory.createEmptyBorder(16, 24, 16, 24) }

        fun runTask(task: () -> String) {
            buttons.components.forEach { it.isEnabled = false }
            log.append("\nOperation started…\n")
            object : SwingWorker<String, Unit>() {
                override fun doInBackground(): String = task()
                override fun done() {
                    try {
                        log.append("\n${get()}\n")
                    } catch (error: Exception) {
                        val cause = error.cause ?: error
                        log.append("\nOperation failed: ${cause.message ?: cause.javaClass.simpleName}\n")
                    } finally {
                        buttons.components.forEach { it.isEnabled = true }
                        log.caretPosition = log.document.length
                    }
                }
            }.execute()
        }

        buttons.add(JButton("Convert files/folders to QZRA").apply {
            addActionListener {
                runTask {
                    val chooser = callOnEdt { JFileChooser().apply { isMultiSelectionEnabled = true; fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES } }
                    check(showOpenChooser(chooser, frame) == JFileChooser.APPROVE_OPTION) { "No source selected" }
                    val sources = callOnEdt { chooser.selectedFiles.toList().ifEmpty { listOf(chooser.selectedFile) } }
                    val masterPassword = requestPassword(frame, "Optional master password (leave blank for an unencrypted archive)")
                    val folderPasswords = if (masterPassword == null) emptyMap() else requestFolderPasswordsForSources(frame, sources)
                    val save = callOnEdt { JFileChooser().apply { selectedFile = File("archive.qzra") } }
                    check(showSaveChooser(save, frame) == JFileChooser.APPROVE_OPTION) { "No destination selected" }
                    val selectedDestination = callOnEdt { save.selectedFile }
                    val destination = if (selectedDestination.extension.equals("qzra", true)) selectedDestination else File(selectedDestination.parentFile, selectedDestination.name + ".qzra")
                    val result = ArchiveEngine.convertToQzra(
                        destination, sources, overwrite = false, password = masterPassword,
                        encryptMetadata = masterPassword != null || folderPasswords.isNotEmpty(), folderPasswords = folderPasswords
                    )
                    val verified = ArchiveEngine.verify(result.archive, masterPassword, folderPasswords)
                    check(verified.valid && verified.filesChecked == result.files) { "Post-conversion verification failed" }
                    "Created and verified ${result.archive.name} (QZRA ${verified.version}): ${result.entries} entries, ${result.files} files, ${result.originalBytes} source bytes. Source files were left unchanged."
                }
            }
        })
        buttons.add(JButton("Inspect / verify").apply {
            addActionListener {
                runTask {
                    val chooser = archiveChooser()
                    check(showOpenChooser(chooser, frame) == JFileChooser.APPROVE_OPTION) { "No archive selected" }
                    val archive = callOnEdt { chooser.selectedFile }
                    val password = requestPassword(frame, "Password for ${archive.name} (leave blank if unencrypted)")
                    val folderPasswords = requestFolderPasswords(frame, archive, password)
                    val entries = ArchiveEngine.list(archive, password, folderPasswords)
                    val verified = ArchiveEngine.verify(archive, password, folderPasswords)
                    "Verified ${archive.name} (${verified.version}): ${verified.filesChecked} files, ${verified.bytesChecked} bytes.\n" +
                        entries.take(40).joinToString("\n") { "${it.type}\t${it.size}\t${it.path}" }
                }
            }
        })
        buttons.add(JButton("Compare archives").apply {
            addActionListener {
                runTask {
                    val firstChooser = archiveChooser()
                    check(showOpenChooser(firstChooser, frame) == JFileChooser.APPROVE_OPTION) { "First archive not selected" }
                    val secondChooser = archiveChooser()
                    check(showOpenChooser(secondChooser, frame) == JFileChooser.APPROVE_OPTION) { "Second archive not selected" }
                    val first = callOnEdt { firstChooser.selectedFile }
                    val second = callOnEdt { secondChooser.selectedFile }
                    val firstPassword = requestPassword(frame, "Password for ${first.name} (leave blank if unencrypted)")
                    val secondPassword = requestPassword(frame, "Password for ${second.name} (leave blank if unencrypted)")
                    val firstFolders = requestFolderPasswords(frame, first, firstPassword)
                    val secondFolders = requestFolderPasswords(frame, second, secondPassword)
                    val report = ArchiveEngine.compareContent(first, second, firstPassword, secondPassword, firstFolders, secondFolders)
                    "Compared and content-verified ${first.name} → ${second.name}:\n" +
                        "SHA-256 verification: ${report.contentVerified}\n" +
                        "Added: ${report.added.size}\nRemoved: ${report.removed.size}\n" +
                        "Changed by SHA-256/type/size: ${report.changed.size}\n" +
                        "Unchanged by verified SHA-256: ${report.unchanged.size}\n" +
                        (report.added.take(20).joinToString("\n") { "+ $it" } + "\n" +
                            report.removed.take(20).joinToString("\n") { "- $it" } + "\n" +
                            report.changed.take(20).joinToString("\n") { "~ $it" })
                }
            }
        })
        buttons.add(JButton("Extract archive").apply {
            addActionListener {
                runTask {
                    val chooser = archiveChooser()
                    check(showOpenChooser(chooser, frame) == JFileChooser.APPROVE_OPTION) { "No archive selected" }
                    val password = requestPassword(frame, "Archive password (leave blank if unencrypted)")
                    val archive = callOnEdt { chooser.selectedFile }
                    val folderPasswords = requestFolderPasswords(frame, archive, password)
                    val output = callOnEdt { JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY } }
                    check(showSaveChooser(output, frame) == JFileChooser.APPROVE_OPTION) { "No destination folder selected" }
                    val outputDirectory = callOnEdt { output.selectedFile }
                    val files = ArchiveEngine.extract(archive, outputDirectory, password = password, folderPasswords = folderPasswords)
                    "Extracted ${files.size} verified files to ${outputDirectory.absolutePath}."
                }
            }
        })
        buttons.add(JButton("About / format support").apply {
            addActionListener { JOptionPane.showMessageDialog(frame, "QAMZOR Desktop 0.9.0\nReads, verifies, compares and extracts QZRA 1.0–1.3.\nCreates QZRA with optional master encryption and independently protected folders.\nThe desktop interface validates folder passwords before payload operations.", "About QAMZOR", JOptionPane.INFORMATION_MESSAGE) }
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

private fun <T> callOnEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    var value: Any? = null
    var failure: Throwable? = null
    SwingUtilities.invokeAndWait {
        try { value = action() } catch (error: Throwable) { failure = error }
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}

private fun showOpenChooser(chooser: JFileChooser, parent: JFrame): Int =
    callOnEdt { chooser.showOpenDialog(parent) }

private fun showSaveChooser(chooser: JFileChooser, parent: JFrame): Int =
    callOnEdt { chooser.showSaveDialog(parent) }

private fun requestPassword(parent: JFrame, prompt: String): String? = callOnEdt {
    val field = JPasswordField(24)
    val result = JOptionPane.showConfirmDialog(parent, arrayOf(JLabel(prompt), field), "QAMZOR security", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
    if (result != JOptionPane.OK_OPTION) throw java.util.concurrent.CancellationException("Operation cancelled by user")
    val chars = field.password
    try { String(chars).ifEmpty { null } } finally { chars.fill('\u0000') }
}

private fun requestFolderPasswords(parent: JFrame, archive: File, masterPassword: String?): Map<String, String> {
    val scopes = ArchiveEngine.protectedFolders(archive, masterPassword)
    if (scopes.isEmpty()) return emptyMap()
    val values = LinkedHashMap<String, String>()
    for (scope in scopes) {
        val value = requestPassword(parent, "Independent password for protected folder: $scope")
        require(!value.isNullOrEmpty()) { "Folder password cannot be empty: $scope" }
        values[scope] = value
    }
    return values
}

private fun requestFolderPasswordsForSources(parent: JFrame, sources: List<File>): Map<String, String> {
    val candidates = LinkedHashSet<String>()
    for (source in sources.filter { it.isDirectory }) {
        for (folder in source.walkTopDown()) {
            if (!folder.isDirectory || java.nio.file.Files.isSymbolicLink(folder.toPath())) continue
            val path = if (folder == source) source.name else source.name + "/" +
                source.toPath().relativize(folder.toPath()).toString().replace(File.separatorChar, '/')
            candidates += path
            if (candidates.size == 32) break
        }
        if (candidates.size == 32) break
    }
    if (candidates.isEmpty()) return emptyMap()
    val list = callOnEdt {
        JList(candidates.toTypedArray()).apply { selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION }
    }
    val result = callOnEdt {
        JOptionPane.showConfirmDialog(parent, JScrollPane(list), "Select folders for independent passwords (optional)", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
    }
    if (result != JOptionPane.OK_OPTION) throw java.util.concurrent.CancellationException("Operation cancelled by user")
    val selected = callOnEdt { list.selectedValuesList }
    if (selected.isEmpty()) return emptyMap()
    val passwords = LinkedHashMap<String, String>()
    for (scope in selected) {
        val value = requestPassword(parent, "Independent password for $scope")
        require(!value.isNullOrEmpty()) { "Folder password cannot be empty: $scope" }
        passwords[scope] = value
    }
    return passwords
}

private fun archiveChooser() = callOnEdt {
    JFileChooser().apply {
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter("QAMZOR archives (*.qzra)", "qzra")
    }
}
