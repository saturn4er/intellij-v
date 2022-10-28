package org.vlang.lang.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parentOfType
import org.vlang.ide.colors.VlangColor
import org.vlang.lang.VlangParserDefinition
import org.vlang.lang.VlangTypes
import org.vlang.lang.completion.VlangCompletionUtil
import org.vlang.lang.psi.*
import org.vlang.lang.psi.impl.VlangReference
import org.vlang.lang.sql.VlangSqlUtil

class VlangAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (holder.isBatchMode) return

        val color = when (element) {
            is LeafPsiElement        -> highlightLeaf(element, holder)
            is VlangSqlBlock         -> VlangColor.SQL_CODE
            is VlangUnsafeExpression -> VlangColor.UNSAFE_CODE
            else                     -> null
        } ?: return

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).textAttributes(color.textAttributesKey).create()
    }

    private fun highlightAttribute(element: PsiElement): VlangColor? {
        if (element.parent is VlangPlainAttribute) {
            if (element.elementType == VlangTypes.IDENTIFIER || element.elementType == VlangTypes.UNSAFE || element.elementType == VlangTypes.SQL) {
                return VlangColor.ATTRIBUTE
            }
        }

        if (element.parent is VlangAttribute && (element.elementType == VlangTypes.LBRACK || element.elementType == VlangTypes.RBRACK)) {
            return VlangColor.ATTRIBUTE
        }

        return null
    }

    private fun highlightLeaf(element: PsiElement, holder: AnnotationHolder): VlangColor? {
        val parent = element.parent as? VlangCompositeElement ?: return null

        if (VlangCompletionUtil.isCompileTimeIdentifier(element)) {
            return VlangColor.CT_CONSTANT
        }

        if (VlangCompletionUtil.isCompileTimeMethodIdentifier(element) && parent.parentOfType<VlangCallExpr>() != null) {
            return VlangColor.CT_METHOD_CALL
        }

        if (parent is VlangReferenceExpression || parent is VlangTypeReferenceExpression) {
            return highlightReference(parent as VlangReferenceExpressionBase, parent.reference as VlangReference)
        }

        if (parent is VlangPlainAttribute || parent is VlangAttribute) {
            return highlightAttribute(element)
        }

        if (VlangSqlUtil.insideSql(element) && VlangSqlUtil.isSqlKeyword(element.text)) {
            return VlangColor.SQL_KEYWORD
        }

        return when (element.elementType) {
            VlangTypes.IDENTIFIER                -> highlightIdentifier(element, parent)
            VlangTypes.LONG_TEMPLATE_ENTRY_START -> VlangColor.STRING_INTERPOLATION
            VlangTypes.LONG_TEMPLATE_ENTRY_END   -> VlangColor.STRING_INTERPOLATION
            else                                 -> null
        }
    }

    private fun highlightReference(element: VlangReferenceExpressionBase, reference: VlangReference): VlangColor? {
        if (element.parent is VlangCallExpr) {
            val name = element.getIdentifier()?.text
            if (VlangParserDefinition.typeCastFunctions.contains(name)) {
                return VlangColor.BUILTIN_TYPE
            }
        }

        val resolved = reference.multiResolve(false).firstOrNull()?.element ?: return null

        return when (resolved) {
            is VlangFunctionDeclaration       -> public(resolved, VlangColor.PUBLIC_FUNCTION, VlangColor.FUNCTION)
            is VlangMethodDeclaration         -> public(resolved, VlangColor.PUBLIC_FUNCTION, VlangColor.FUNCTION)
            is VlangInterfaceMethodDefinition -> public(resolved, VlangColor.INTERFACE_METHOD, VlangColor.INTERFACE_METHOD)
            is VlangStructDeclaration         -> public(resolved, VlangColor.PUBLIC_STRUCT, VlangColor.STRUCT)
            is VlangEnumDeclaration           -> public(resolved, VlangColor.PUBLIC_ENUM, VlangColor.ENUM)
            is VlangUnionDeclaration          -> public(resolved, VlangColor.PUBLIC_UNION, VlangColor.UNION)
            is VlangInterfaceDeclaration      -> public(resolved, VlangColor.PUBLIC_INTERFACE, VlangColor.INTERFACE)
            is VlangConstDefinition           -> public(resolved, VlangColor.PUBLIC_CONSTANT, VlangColor.CONSTANT)
            is VlangFieldDefinition           -> public(resolved, VlangColor.PUBLIC_FIELD, VlangColor.FIELD)
            is VlangEnumFieldDefinition       -> public(resolved, VlangColor.ENUM_FIELD, VlangColor.ENUM_FIELD)
            is VlangTypeAliasDeclaration      -> public(resolved, VlangColor.PUBLIC_TYPE_ALIAS, VlangColor.TYPE_ALIAS)
            is VlangParamDefinition           -> mutable(resolved, VlangColor.MUTABLE_PARAMETER, VlangColor.PARAMETER)
            is VlangReceiver                  -> mutable(resolved, VlangColor.MUTABLE_RECEIVER, VlangColor.RECEIVER)
            is VlangGlobalVariableDefinition  -> VlangColor.GLOBAL_VARIABLE
            is VlangVarDefinition             -> if (resolved.isCaptured(element)) {
                mutable(resolved, VlangColor.MUTABLE_CAPTURED_VARIABLE, VlangColor.CAPTURED_VARIABLE)
            } else {
                mutable(resolved, VlangColor.MUTABLE_VARIABLE, VlangColor.VARIABLE)
            }

            else                              -> null
        }
    }

    private fun highlightIdentifier(element: PsiElement, parent: VlangCompositeElement): VlangColor? {
        val grand = parent.parent
        if (grand is VlangCompositeElement && grand is PsiNameIdentifierOwner && grand.nameIdentifier == element) {
            return colorFor(grand)
        }

        return when {
            parent is VlangMethodName && parent.identifier == element            -> colorFor(parent.parent as VlangMethodDeclaration)
            parent is PsiNameIdentifierOwner && parent.nameIdentifier == element -> colorFor(parent)

            element.text in listOf(
                "_likely_",
                "_unlikely_",
                "sizeof",
                "__offsetof",
                "typeof",
                "sql",
            )                                                                    -> VlangColor.KEYWORD

            else                                                                 -> null
        }
    }

    private fun colorFor(element: VlangCompositeElement): VlangColor? = when (element) {
        is VlangFunctionOrMethodDeclaration -> public(element, VlangColor.PUBLIC_FUNCTION, VlangColor.FUNCTION)
        is VlangConstDefinition             -> public(element, VlangColor.PUBLIC_CONSTANT, VlangColor.CONSTANT)
        is VlangTypeAliasDeclaration        -> public(element, VlangColor.PUBLIC_TYPE_ALIAS, VlangColor.TYPE_ALIAS)
        is VlangStructDeclaration           -> public(element, VlangColor.PUBLIC_STRUCT, VlangColor.STRUCT)
        is VlangInterfaceDeclaration        -> public(element, VlangColor.PUBLIC_INTERFACE, VlangColor.INTERFACE)
        is VlangEnumDeclaration             -> public(element, VlangColor.PUBLIC_ENUM, VlangColor.ENUM)
        is VlangUnionDeclaration            -> public(element, VlangColor.PUBLIC_UNION, VlangColor.UNION)

        is VlangFieldDefinition             -> public(element, VlangColor.PUBLIC_FIELD, VlangColor.FIELD)
        is VlangInterfaceMethodDefinition   -> public(element, VlangColor.INTERFACE_METHOD, VlangColor.INTERFACE_METHOD)
        is VlangEnumFieldDefinition         -> public(element, VlangColor.ENUM_FIELD, VlangColor.ENUM_FIELD)

        is VlangVarDefinition               -> mutable(element, VlangColor.MUTABLE_VARIABLE, VlangColor.VARIABLE)
        is VlangReceiver                    -> mutable(element, VlangColor.MUTABLE_VARIABLE, VlangColor.VARIABLE)
        is VlangParamDefinition             -> mutable(element, VlangColor.MUTABLE_VARIABLE, VlangColor.VARIABLE)
        is VlangGlobalVariableDefinition    -> VlangColor.GLOBAL_VARIABLE

        is VlangLabelDefinition             -> label(element)
        is VlangLabelRef                    -> VlangColor.LABEL

        else                                -> null
    }

    private fun label(element: VlangLabelDefinition): VlangColor {
        val parent = element.parent
        val search = ReferencesSearch.search(parent, parent.useScope)
        return if (search.findFirst() != null) {
            VlangColor.USED_LABEL
        } else {
            VlangColor.LABEL
        }
    }

    private fun public(element: VlangNamedElement, ifPublic: VlangColor, ifNotPublic: VlangColor): VlangColor {
        return if (element.isPublic()) ifPublic else ifNotPublic
    }

    private fun mutable(element: VlangMutable, ifMutable: VlangColor, ifNoMutable: VlangColor): VlangColor {
        return if (element.isMutable()) ifMutable else ifNoMutable
    }
}
