/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesHandler
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil
import com.intellij.refactoring.util.CommonRefactoringUtil
import org.rust.ide.experiments.RsExperiments
import org.rust.lang.RsLanguage
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.isEdition2018
import org.rust.lang.core.psi.hasChildModules
import org.rust.openapiext.isFeatureEnabled

class RsMoveFilesOrDirectoriesHandler : MoveFilesOrDirectoriesHandler() {

    override fun supportsLanguage(language: Language): Boolean = language.`is`(RsLanguage)

    override fun adjustTargetForMove(dataContext: DataContext?, targetContainer: PsiElement?): PsiElement? {
        return (targetContainer as? RsFile)?.containingDirectory ?: targetContainer
    }

    private fun PsiElement.canMove(): Boolean {
        if (this !is RsFile) return false

        // TODO: support move files with its child modules
        if (ownsDirectory) return false
        if (hasChildModules()) return false

        // TODO: support path attribute on mod declaration
        if (pathAttribute != null) return false

        return modName != null
            && crateRoot != null
            && crateRelativePath != null
            && !isCrateRoot
            && isEdition2018  // TODO: support 2015 edition
    }

    override fun canMove(elements: Array<out PsiElement>, targetContainer: PsiElement?, reference: PsiReference?): Boolean {
        if (!isFeatureEnabled(RsExperiments.MOVE_REFACTORING)) return false
        if (!elements.all { it.canMove() }) return false

        // TODO: support move multiply files
        if (elements.size > 1) return false

        val adjustedTargetContainer = adjustTargetForMove(null, targetContainer)
        return super.canMove(elements, adjustedTargetContainer, reference)
    }

    override fun doMove(project: Project, elements: Array<out PsiElement>, targetContainer: PsiElement?, moveCallback: MoveCallback?) {
        if (!CommonRefactoringUtil.checkReadOnlyStatusRecursively(project, elements.toList(), true)) return

        val adjustedTargetContainer = adjustTargetForMove(null, targetContainer)
        val adjustedElements = adjustForMove(project, elements, adjustedTargetContainer) ?: return

        val targetDirectory = MoveFilesOrDirectoriesUtil.resolveToDirectory(project, adjustedTargetContainer)
        if (adjustedTargetContainer != null && targetDirectory == null) return
        val initialTargetDirectory = MoveFilesOrDirectoriesUtil.getInitialTargetDirectory(targetDirectory, elements)

        RsMoveFilesOrDirectoriesDialog(project, adjustedElements, initialTargetDirectory, moveCallback).show()
    }

    override fun tryToMove(
        element: PsiElement,
        project: Project,
        dataContext: DataContext?,
        reference: PsiReference?,
        editor: Editor?
    ): Boolean {
        if (!canMove(arrayOf(element), null, reference)) return false

        return super.tryToMove(element, project, dataContext, reference, editor)
    }
}
