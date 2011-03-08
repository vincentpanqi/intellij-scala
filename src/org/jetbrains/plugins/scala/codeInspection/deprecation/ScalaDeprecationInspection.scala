package org.jetbrains.plugins.scala
package codeInspection.deprecation

import org.jetbrains.plugins.scala.codeInspection.InspectionsUtil
import com.intellij.psi._
import com.intellij.codeInspection.{ProblemHighlightType, ProblemsHolder, LocalInspectionTool}
import lang.psi.ScalaPsiUtil
import lang.psi.api.ScalaElementVisitor
import lang.psi.api.statements.{ScFunction, ScAnnotationsHolder}
import lang.psi.api.base.{ScReferenceElement, ScPrimaryConstructor}


/**
 * User: Alexander Podkhalyuzin
 * Date: 13.04.2010
 */

class ScalaDeprecationInspection extends LocalInspectionTool {
  override def buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = {
    def checkDeprecated(refElement: PsiElement, elementToHighlight: PsiElement, name: String): Unit = {
      if (refElement == null) return
      if (!refElement.isInstanceOf[PsiNamedElement]) return
      val context = ScalaPsiUtil.nameContext(refElement.asInstanceOf[PsiNamedElement])
      context match {
        case doc: PsiDocCommentOwner => {
          doc match {
            case _: ScPrimaryConstructor =>
            case f: PsiMethod if f.isConstructor =>
            case _ => if (!doc.isDeprecated) return
          }
          if (!doc.isDeprecated && !doc.getContainingClass.isDeprecated) return
        }
        case _ => return
      }
      val message = for {
        holder <- context.asOptionOf(classOf[ScAnnotationsHolder])
        annotation <- holder.hasAnnotation("scala.deprecated")
        message <- ScalaPsiUtil.readAttribute(annotation, "value")
      } yield message
      
      val description: String = Seq(Some("Symbol " + name + " is deprecated"),  message).flatten.mkString(". ")
      holder.registerProblem(holder.getManager.createProblemDescriptor(elementToHighlight, description, true,
        ProblemHighlightType.LIKE_DEPRECATED))
    }

    new ScalaElementVisitor {
      override def visitFunction(fun: ScFunction): Unit = {
        //todo: check super method is deprecated
      }

      override def visitReference(ref: ScReferenceElement): Unit = {
        checkDeprecated(ref.resolve, ref.nameId, ref.refName)
      }
    }
  }

  def getDisplayName: String = {
    return "Scala Deprecation"
  }

  def getGroupDisplayName: String = {
    return InspectionsUtil.SCALA
  }

  def getShortName: String = {
    return "Scala Deprecation"
  }

  override def getID: String = {
    return "ScalaDeprecation"
  }

  override def isEnabledByDefault: Boolean = {
    return true
  }

  override def getStaticDescription: String = "This inspection reports where deprecated code is used in the specified inspection scope."
}