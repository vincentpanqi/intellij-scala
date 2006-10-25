package org.jetbrains.plugins.scala.lang.psi.impl.expressions {
/**
* @author Ilya Sergey
* PSI implementation for auxiliary expressions
*/
import com.intellij.lang.ASTNode

import org.jetbrains.plugins.scala.lang.psi._

  case class ScArgumentExprsImpl( node : ASTNode ) extends ScalaPsiElementImpl(node) {
      override def toString: String = "Argument expressions "+ getText
  }

}