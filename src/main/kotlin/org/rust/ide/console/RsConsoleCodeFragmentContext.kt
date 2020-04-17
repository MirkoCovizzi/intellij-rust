/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.console

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import org.rust.cargo.project.model.cargoProjects
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.openapiext.toPsiFile

class RsConsoleCodeFragmentContext {

    private val itemsNames: Set<String> = mutableSetOf()
    private val commands: MutableList<String> = mutableListOf()

    fun addToContext(lastCommandContext: RsConsoleOneCommandContext) {
        if (commands.isEmpty() || lastCommandContext.itemsNames.any(itemsNames::contains)) {
            commands.add(lastCommandContext.command)
        } else {
            commands[commands.size - 1] = commands.last() + "\n" + lastCommandContext.command
        }
    }

    fun updateContext(project: Project, codeFragment: RsReplCodeFragment) {
        DumbService.getInstance(project).smartInvokeLater {
            runWriteAction {
                codeFragment.context = createContext(project, codeFragment.crateRoot as RsFile?, commands)
            }
        }
    }

    fun getAllCommandsText(): String {
        return commands.joinToString("\n")
    }

    companion object {
        fun createContext(project: Project, originalCrateRoot: RsFile?, commands: List<String> = listOf("")): RsBlock {
            // command may contain functions/structs with same names as in previous commands
            // therefore we should put such commands in separate scope
            // we do this like so:
            // ```
            // command1;
            // {
            //     command2;
            //     {
            //         command3;
            //     }
            // }
            // And `RsBlock` surrounding `command3` will become context of codeFragment
            // ```
            val functionBody = commands.joinToString("\n{\n") + "\n}".repeat(commands.size - 1)
            val rsFile = RsPsiFactory(project).createFile("fn main() { $functionBody }")

            val crateRoot = originalCrateRoot ?: findAnyCrateRoot(project)
            crateRoot?.let { rsFile.originalFile = crateRoot }

            var block: RsBlock = rsFile.childOfType<RsFunction>()!!.block!!
            repeat(commands.size - 1) {
                val blockExpr = block.childrenOfType<RsElement>().last() as RsBlockExpr
                block = blockExpr.block
            }
            return block
        }

        private fun findAnyCrateRoot(project: Project): RsFile? {
            val cargoProject = project.cargoProjects.allProjects.first()
            val crateRoot = cargoProject.workspace?.packages?.firstOrNull()?.targets?.firstOrNull()?.crateRoot
            return crateRoot?.toPsiFile(project)?.rustFile
        }
    }
}

class RsConsoleOneCommandContext(codeFragment: RsReplCodeFragment) {
    val command: String
    val itemsNames: List<String>

    init {
        val elements = codeFragment.expandedStmtsAndTailExpr.first
        command = elements.joinToString("\n") { it.text }

        itemsNames = elements.filterIsInstance<RsNamedElement>()
            .mapNotNull { it.name }
    }
}
