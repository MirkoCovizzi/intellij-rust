/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parents
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.refactoring.util.RefactoringUIUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*

class RsMoveFilesOrDirectoriesConflictsDetector(
    private val movedFile: RsFile,
    private val newParentMod: RsMod,
    private val outsideReferencesMap: Map<RsPath, RsElement>
) {

    var shouldMakeMovedFileModDeclarationPublic: Boolean = false
        private set

    fun detectVisibilityProblems(usages: Array<UsageInfo>, conflicts: MultiMap<PsiElement, String>) {
        detectInsideReferencesVisibilityProblems(usages, conflicts)
        detectOutsideReferencesVisibilityProblems(conflicts)
    }

    private fun detectInsideReferencesVisibilityProblems(usages: Array<UsageInfo>, conflicts: MultiMap<PsiElement, String>) {
        // for each reference we should check that:
        // - newParentMod is accessible
        // - movedFile mod-declaration in newParentMod is accessible
        //   either `pub` or `pub(in ...)`, otherwise we should add `pub`
        // - items in movedFile can have `pub(in ...)` visibility,
        //   but we will update them in performRefactoring

        shouldMakeMovedFileModDeclarationPublic = false
        for (usage in usages) {
            val usageElement = usage.element as? RsPath ?: continue
            val usageMod = usageElement.containingMod

            // TODO: better support for reexports
            for (superMod in newParentMod.superMods.asReversed()) {
                if (!superMod.isVisibleFrom(usageMod)) {
                    addVisibilityConflict(conflicts, usageElement, movedFile)
                    break
                }
            }

            if (!usageMod.superMods.contains(newParentMod)) {
                shouldMakeMovedFileModDeclarationPublic = true
            }
        }
    }

    private fun detectOutsideReferencesVisibilityProblems(conflicts: MultiMap<PsiElement, String>) {
        for ((path, target) in outsideReferencesMap) {
            val isInsideVisRestriction = path.parents().first { it !is RsPath } is RsVisRestriction
            if (isInsideVisRestriction || target.crateRoot != movedFile.crateRoot) continue

            if (path.hasOnlySuperSegments()) {
                val pathParent = path.parent
                if (pathParent is RsPath && pathParent.hasOnlySuperSegments()) continue

                val targetMod = target as? RsMod ?: continue
                for (mod in targetMod.superMods) {
                    if (!mod.isVisibleFrom(newParentMod)) {
                        addVisibilityConflict(conflicts, path, target)
                        break
                    }
                }
            } else {
                checkVisibility(conflicts, path)
            }
        }

        detectPrivateStructFieldOutsideReferences(conflicts)
    }

    private fun detectPrivateStructFieldOutsideReferences(conflicts: MultiMap<PsiElement, String>) {
        fun checkStructFieldVisibility(fieldReference: RsMandatoryReferenceElement) {
            val field = fieldReference.reference.resolve() ?: return
            if (!field.isInsideModSubtree(movedFile)) checkVisibility(conflicts, fieldReference)
        }
        for (dotExpr in movedFile.descendantsOfType<RsDotExpr>()) {
            val fieldReference = dotExpr.fieldLookup ?: dotExpr.methodCall ?: continue
            checkStructFieldVisibility(fieldReference)
        }
        for (fieldReference in movedFile.descendantsOfType<RsStructLiteralField>()) {
            checkStructFieldVisibility(fieldReference)
        }
        for (patField in movedFile.descendantsOfType<RsPatField>()) {
            val patBinding = patField.patBinding ?: continue
            checkStructFieldVisibility(patBinding)
        }

        for (patTupleStruct in movedFile.descendantsOfType<RsPatTupleStruct>()) {
            val struct = patTupleStruct.path.reference?.resolve() as? RsStructItem ?: continue
            if (struct.isInsideModSubtree(movedFile)) continue
            val fields = struct.tupleFields?.tupleFieldDeclList ?: continue
            if (!fields.all { it.isVisibleFrom(newParentMod) }) {
                addVisibilityConflict(conflicts, patTupleStruct, struct)
            }
        }
    }

    private fun checkVisibility(conflicts: MultiMap<PsiElement, String>, referenceElement: RsReferenceElement): Boolean {
        val target = referenceElement.reference?.resolve() as? RsVisible ?: return true
        if (target.isVisibleFrom(newParentMod)) return true

        addVisibilityConflict(conflicts, referenceElement, target)
        return false
    }

    private fun addVisibilityConflict(conflicts: MultiMap<PsiElement, String>, reference: RsElement, target: RsElement) {
        val referenceDescription = RefactoringUIUtil.getDescription(reference.containingMod, true)
        val targetDescription = RefactoringUIUtil.getDescription(target, true)
        val message = "$referenceDescription uses $targetDescription which will be inaccessible after move"
        conflicts.putValue(reference, CommonRefactoringUtil.capitalize(message))
    }
}
