package org.jetbrains.plugins.scala
package lang
package psi
package types

import impl.ScalaPsiManager
import impl.toplevel.synthetic.ScSyntheticClass
import nonvalue.NonValueType
import com.intellij.psi._
import com.intellij.psi.search.GlobalSearchScope
import impl.source.PsiImmediateClassType
import result.{Failure, Success, TypingContext}
import com.intellij.openapi.project.Project
import api.statements._
import light.PsiClassWrapper
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.PsiClassType.ClassResolveResult
import api.toplevel.typedef.{ScTypeDefinition, ScObject}
import extensions.{toPsiMemberExt, toPsiClassExt}
import collection.mutable.ArrayBuffer
import java.util
import psi.types

trait ScTypePsiTypeBridge {
  /**
   * @param treatJavaObjectAsAny if true, and paramTopLevel is true, java.lang.Object is treated as scala.Any
   *                             See SCL-3036 and SCL-2375
   */
  def create(psiType: PsiType, project: Project, scope: GlobalSearchScope = null, deep: Int = 0,
             paramTopLevel: Boolean = false, treatJavaObjectAsAny: Boolean = true): ScType = {
    if (deep > 3) // Cranked up from 2 to 3 to solve SCL-2976. But why is this really needed?
      return types.Any

    psiType match {
      case classType: PsiClassType => {
        val result = classType.resolveGenerics
        result.getElement match {
          case tp: PsiTypeParameter => ScalaPsiManager.typeVariable(tp)
          case clazz if clazz != null && clazz.qualifiedName == "java.lang.Object" => {
            if (paramTopLevel && treatJavaObjectAsAny) types.Any
            else types.AnyRef
          }
          case c if c != null => {
            val clazz = c match {
              case o: ScObject => ScalaPsiUtil.getCompanionModule(o).getOrElse(o)
              case _ => c
            }
            val tps = clazz.getTypeParameters
            def constructTypeForClass(clazz: PsiClass): ScType = {
              clazz match {
                case wrapper: PsiClassWrapper => return constructTypeForClass(wrapper.definition)
                case _ =>
              }
              val containingClass: PsiClass = clazz.containingClass
              if (containingClass == null) {
                ScDesignatorType(clazz)
              } else {
                ScProjectionType(constructTypeForClass(containingClass), clazz, superReference = false)
              }
            }
            val des = constructTypeForClass(clazz)
            val substitutor = result.getSubstitutor
            tps match {
              case Array() => des
              case _ if classType.isRaw => {
                var index = 0
                new ScParameterizedType(des, collection.immutable.Seq(tps.map({tp => {
                  val arrayOfTypes: Array[PsiClassType] = tp.getExtendsListTypes ++ tp.getImplementsListTypes
                  ScSkolemizedType(s"_$$${index += 1; index}", Nil, types.Nothing,
                    arrayOfTypes.length match {
                      case 0 => types.Any
                      case 1 => create(arrayOfTypes.apply(0), project, scope, deep + 1)
                      case _ => ScCompoundType(arrayOfTypes.map(create(_, project, scope, deep + 1)), Seq.empty, Seq.empty, ScSubstitutor.empty)
                    })
              }}): _*)).unpackedType
              }
              case _ =>
                var index = 0
                new ScParameterizedType(des, collection.immutable.Seq(tps.map
                  (tp => {
                    val psiType = substitutor.substitute(tp)
                    psiType match {
                      case wild: PsiWildcardType => ScSkolemizedType(s"_$$${index += 1; index}", Nil,
                        if (wild.isSuper) create(wild.getSuperBound, project, scope, deep + 1) else types.Nothing,
                        if (wild.isExtends) create(wild.getExtendsBound, project, scope, deep + 1) else types.Any)
                      case capture: PsiCapturedWildcardType =>
                        val wild = capture.getWildcard
                        ScSkolemizedType(s"_$$${index += 1; index}", Nil,
                          if (wild.isSuper) create(capture.getLowerBound, project, scope) else types.Nothing,
                          if (wild.isExtends) create(capture.getUpperBound, project, scope) else types.Any)
                      case _ if psiType != null => ScType.create(psiType, project, scope, deep + 1)
                      case _ => ScalaPsiManager.typeVariable(tp)
                    }
                  }).toSeq: _*)).unpackedType
            }
          }
          case _ => types.Nothing
        }
      }
      case arrayType: PsiArrayType =>
        JavaArrayType(create(arrayType.getComponentType, project, scope))
      case PsiType.VOID => types.Unit
      case PsiType.BOOLEAN => types.Boolean
      case PsiType.CHAR => types.Char
      case PsiType.INT => types.Int
      case PsiType.LONG => types.Long
      case PsiType.FLOAT => types.Float
      case PsiType.DOUBLE => types.Double
      case PsiType.BYTE => types.Byte
      case PsiType.SHORT => types.Short
      case PsiType.NULL => types.Null
      case wild: PsiWildcardType => ScExistentialType.simpleExistential("_$1", Nil,
        if (wild.isSuper) create(wild.getSuperBound, project, scope, deep + 1) else types.Nothing,
        if (wild.isExtends) create(wild.getExtendsBound, project, scope, deep + 1) else types.Any)
      case capture: PsiCapturedWildcardType =>
        val wild = capture.getWildcard
        ScExistentialType.simpleExistential("_$1", Nil,
          if (wild.isSuper) create(capture.getLowerBound, project, scope) else types.Nothing,
          if (wild.isExtends) create(capture.getUpperBound, project, scope) else types.Any)
      case null => types.Any
      case d: PsiDisjunctionType => types.Any
      case d: PsiDiamondType =>
        val tps: util.List[PsiType] = d.resolveInferredTypes().getInferredTypes
        if (tps.size() > 0) {
          create(tps.get(0), project, scope, deep, paramTopLevel, treatJavaObjectAsAny)
        } else {
          if (paramTopLevel && treatJavaObjectAsAny) types.Any
          else types.AnyRef
        }
      case _ => throw new IllegalArgumentException("psi type " + psiType + " should not be converted to scala type")
    }
  }

  def toPsi(t: ScType, project: Project, scope: GlobalSearchScope, noPrimitives: Boolean = false,
            skolemToWildcard: Boolean = false): PsiType = {
    if (t.isInstanceOf[NonValueType]) return toPsi(t.inferValueType, project, scope)
    def javaObj = JavaPsiFacade.getInstance(project).getElementFactory.createTypeByFQClassName("java.lang.Object", scope)
    t match {
      case types.Any => javaObj
      case types.AnyRef => javaObj
      case types.Unit => if (noPrimitives) javaObj else PsiType.VOID
      case types.Boolean => if (noPrimitives) javaObj else PsiType.BOOLEAN
      case types.Char => if (noPrimitives) javaObj else PsiType.CHAR
      case types.Int => if (noPrimitives) javaObj else PsiType.INT
      case types.Long => if (noPrimitives) javaObj else PsiType.LONG
      case types.Float => if (noPrimitives) javaObj else PsiType.FLOAT
      case types.Double => if (noPrimitives) javaObj else PsiType.DOUBLE
      case types.Byte => if (noPrimitives) javaObj else PsiType.BYTE
      case types.Short => if (noPrimitives) javaObj else PsiType.SHORT
      case types.Null => javaObj
      case types.Nothing => javaObj
      case fun: ScFunctionType => fun.resolveFunctionTrait(project) match {
        case Some(tp) => toPsi(tp, project, scope) case _ => javaObj
      }
      case tuple: ScTupleType => tuple.resolveTupleTrait(project) match {
        case Some(tp) => toPsi(tp, project, scope) case _ => javaObj
      }
      case ScCompoundType(Seq(t, _*), _, _, _) => toPsi(t, project, scope)
      case ScDesignatorType(c: ScTypeDefinition) if ScType.baseTypesQualMap.contains(c.qualifiedName) =>
        toPsi(ScType.baseTypesQualMap.get(c.qualifiedName).get, project, scope, noPrimitives, skolemToWildcard)
      case ScDesignatorType(c: PsiClass) => JavaPsiFacade.getInstance(project).getElementFactory.createType(c, PsiSubstitutor.EMPTY)
      case ScParameterizedType(ScDesignatorType(c: PsiClass), args) =>
        if (c.qualifiedName == "scala.Array" && args.length == 1)
          new PsiArrayType(toPsi(args(0), project, scope))
        else {
          val subst = args.zip(c.getTypeParameters).foldLeft(PsiSubstitutor.EMPTY)
                    {case (s, (targ, tp)) => s.put(tp, toPsi(targ, project, scope, noPrimitives = true, skolemToWildcard = true))}
          JavaPsiFacade.getInstance(project).getElementFactory.createType(c, subst)
        }
      case ScParameterizedType(proj@ScProjectionType(pr, element, _), args) => proj.actualElement match {
        case c: PsiClass => if (c.qualifiedName == "scala.Array" && args.length == 1)
          new PsiArrayType(toPsi(args(0), project, scope))
        else {
          val subst = args.zip(c.getTypeParameters).foldLeft(PsiSubstitutor.EMPTY)
                    {case (s, (targ, tp)) => s.put(tp, toPsi(targ, project, scope, skolemToWildcard = true))}
          JavaPsiFacade.getInstance(project).getElementFactory.createType(c, subst)
        }
        case a: ScTypeAliasDefinition =>
          a.aliasedType(TypingContext.empty) match {
            case Success(c: ScParameterizedType, _) =>
              toPsi(c.copy(typeArgs = args), project, scope, noPrimitives)
            case _ => javaObj
          }
        case _ => javaObj
      }
      case JavaArrayType(arg) => new PsiArrayType(toPsi(arg, project, scope))
      case proj@ScProjectionType(pr, element, _) => proj.actualElement match {
        case clazz: PsiClass => {
          clazz match {
            case syn: ScSyntheticClass => toPsi(syn.t, project, scope)
            case _ => {
              val fqn = clazz.qualifiedName
              JavaPsiFacade.getInstance(project).getElementFactory.createTypeByFQClassName(if (fqn != null) fqn else "java.lang.Object", scope)
            }
          }
        }
        case elem: ScTypeAliasDefinition => {
          elem.aliasedType(TypingContext.empty) match {
            case Success(t, _) => toPsi(t, project, scope, noPrimitives)
            case Failure(_, _) => javaObj
          }
        }
        case _ => javaObj
      }
      case ScThisType(clazz) => JavaPsiFacade.getInstance(project).getElementFactory.createType(clazz, PsiSubstitutor.EMPTY)
      case tpt: ScTypeParameterType =>
        EmptySubstitutor.getInstance().substitute(tpt.param)
      case ex: ScExistentialType => toPsi(ex.skolem, project, scope, noPrimitives)
      case argument: ScSkolemizedType =>
        val upper = argument.upper
        if (upper.equiv(types.Any)) {
          val lower = argument.lower
          if (lower.equiv(types.Nothing)) PsiWildcardType.createUnbounded(PsiManager.getInstance(project))
          else {
            PsiWildcardType.createSuper(PsiManager.getInstance(project), toPsi(lower, project, scope))
          }
        } else {
          val psi = toPsi(upper, project, scope)
          if (psi.isInstanceOf[PsiWildcardType]) javaObj
          else PsiWildcardType.createExtends(PsiManager.getInstance(project), psi)
        }
      case _ => javaObj
    }
  }
}

