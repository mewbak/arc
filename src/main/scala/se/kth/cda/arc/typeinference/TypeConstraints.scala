package se.kth.cda.arc.typeinference

import se.kth.cda.arc._
import AST._
import Types._
import scala.collection.mutable
import scala.util.{ Try, Success, Failure }

sealed trait TypeConstraint {
  type Self <: TypeConstraint;
  def describe: String;
  def substitute(assignments: Map[Int, Type]): Option[Self];
  def normalise(): Option[TypeConstraint];
  def variables(): List[TypeVariable];
  def merge(c: TypeConstraint): Option[TypeConstraint];
  //  def isResolved: Boolean;
  //  def resolvedType: Option[Type];
}

object TypeConstraints {

  def substituteType(t: Type, assignments: Map[Int, Type]): Option[Type] = {
    t match {
      case TypeVariable(id) => assignments.get(id)
      case Vec(elemTy)      => substituteType(elemTy, assignments).map(ty => Vec(ty))
      case Dict(keyTy, valueTy) => {
        val newKeyTy = substituteType(keyTy, assignments);
        val newValueTy = substituteType(valueTy, assignments);
        if (newKeyTy.isEmpty && newValueTy.isEmpty) {
          None
        } else {
          Some(Dict(newKeyTy.getOrElse(keyTy), newValueTy.getOrElse(valueTy)))
        }
      }
      case Struct(elemTys) => {
        val newTys = elemTys.map(substituteType(_, assignments));
        if (newTys.forall(_.isEmpty)) {
          None
        } else {
          val newElemTys = elemTys.zip(newTys).map {
            case (_, Some(newTy)) => newTy
            case (oldTy, None)    => oldTy
          };
          Some(Struct(newElemTys))
        }
      }
      case Simd(elemTy)   => substituteType(elemTy, assignments).map(ty => Simd(ty))
      case Stream(elemTy) => substituteType(elemTy, assignments).map(ty => Stream(ty))
      case Function(params, returnTy) => {
        val newParamTys = params.map(substituteType(_, assignments));
        val newReturnTy = substituteType(returnTy, assignments);
        if (newParamTys.forall(_.isEmpty) && newReturnTy.isEmpty) {
          None
        } else {
          val newParams = params.zip(newParamTys).map {
            case (_, Some(newTy)) => newTy
            case (oldTy, None)    => oldTy
          };
          Some(Function(newParams, newReturnTy.getOrElse(returnTy)))
        }
      }
      case Builders.Appender(elemTy, annots) => substituteType(elemTy, assignments).map(ty =>
        Builders.Appender(ty, annots))
      case Builders.StreamAppender(elemTy, annots) => substituteType(elemTy, assignments).map(ty =>
        Builders.StreamAppender(ty, annots))
      case Builders.Merger(elemTy, opTy, annots) => substituteType(elemTy, assignments).map(ty =>
        Builders.Merger(ty, opTy, annots))
      case Builders.DictMerger(keyTy, valueTy, opTy, annots) => {
        val newKeyTy = substituteType(keyTy, assignments);
        val newValueTy = substituteType(valueTy, assignments);
        if (newKeyTy.isEmpty && newValueTy.isEmpty) {
          None
        } else {
          Some(Builders.DictMerger(newKeyTy.getOrElse(keyTy), newValueTy.getOrElse(valueTy), opTy, annots))
        }
      }
      case Builders.VecMerger(elemTy, opTy, annots) => substituteType(elemTy, assignments).map(ty =>
        Builders.VecMerger(ty, opTy, annots))
      case Builders.GroupMerger(keyTy, valueTy, annots) => {
        val newKeyTy = substituteType(keyTy, assignments);
        val newValueTy = substituteType(valueTy, assignments);
        if (newKeyTy.isEmpty && newValueTy.isEmpty) {
          None
        } else {
          Some(Builders.GroupMerger(newKeyTy.getOrElse(keyTy), newValueTy.getOrElse(valueTy), annots))
        }
      }
      case c: ConcreteType => None
    }
  }

  abstract class Predicate(t: Type) extends TypeConstraint {
    def newFromType(t: Type): Self;
    override def substitute(assignments: Map[Int, Type]): Option[Self] = {
      substituteType(t, assignments).map(ty => newFromType(ty))
    }
    override def normalise(): Option[TypeConstraint] = {
      if (isSatisfied) {
        Some(Tautology)
      } else {
        None
      }
    }
    override def variables(): List[TypeVariable] = t match {
      case tv: TypeVariable => List(tv)
      case _                => List.empty
    }
    override def merge(c: TypeConstraint): Option[TypeConstraint] = None; // can't coalesce predicates
    def isSatisfied: Boolean;
  }

  // Basically a subtyping constraint t <= Num
  case class IsNumeric(t: Type, signed: Boolean = false) extends Predicate(t) {
    override type Self = IsNumeric;
    override def describe: String = if (signed) {
      s"numeric±(${t.render})";
    } else {
      s"numeric(${t.render})";
    }
    override def newFromType(t: Type): Self = IsNumeric(t, signed);
    def isSatisfied: Boolean = t match {
      case I8 | I16 | I32 | I64 | F32 | F64 => true
      case U8 | U16 | U32 | U64             => !signed
      case _                                => false
    }
  }

  // Basically a subtyping constraint t <= Val
  case class IsScalar(t: Type) extends Predicate(t) {
    override type Self = IsScalar;
    override def describe: String = s"scalar(${t.render})";
    override def newFromType(t: Type): Self = IsScalar(t);
    def isSatisfied: Boolean = t match {
      case I8 | I16 | I32 | I64 | U8 | U16 | U32 | U64 | F32 | F64 | Bool => true
      case _ => false
    }
  }

  // Basically a subtyping constraint t <= Float
  case class IsFloat(t: Type) extends Predicate(t) {
    override type Self = IsFloat;
    override def describe: String = s"float(${t.render})";
    override def newFromType(t: Type): Self = IsFloat(t);
    def isSatisfied: Boolean = t match {
      case F32 | F64 => true
      case _         => false
    }
  }

  case class ProjectableKind(structTy: Type, fieldTy: Type, fieldIndex: Int) extends TypeConstraint {
    override type Self = ProjectableKind;
    override def describe: String = {
      val loi = (0 until fieldIndex).foldLeft("")((acc, _) => acc + "_,");
      val roi = ",...";
      s"${structTy.render}≼{${loi}${fieldTy.render}${roi}}"
    };
    override def substitute(assignments: Map[Int, Type]): Option[Self] = {
      val stO = substituteType(structTy, assignments);
      val ftO = substituteType(fieldTy, assignments);
      if (stO.isEmpty && ftO.isEmpty) {
        None
      } else {
        val newST = stO.getOrElse(structTy);
        val newFT = ftO.getOrElse(fieldTy);
        Some(ProjectableKind(newST, newFT, fieldIndex))
      }
    }

    override def normalise(): Option[TypeConstraint] = {
      structTy match {
        case Struct(args) if args.size > fieldIndex => {
          Some(MultiEquality(List(args(fieldIndex), fieldTy)))
        }
        case tv: TypeVariable => None // may be solved later
        case _                => None // won't be solved but leave like this for useful error reporting
      }
    }

    override def variables(): List[TypeVariable] = structTy match {
      case tv: TypeVariable => List(tv)
      case _                => List.empty
    }

    override def merge(c: TypeConstraint): Option[TypeConstraint] = {
      c match {
        case ProjectableKind(otherStructTy, otherFieldTy, otherFieldIndex) => {
          if (otherStructTy == this.structTy && otherFieldIndex == this.fieldIndex) {
            var conj = List.empty[TypeConstraint];
            conj ::= this;
            conj ::= MultiEquality(List(otherFieldTy, this.fieldTy));
            Some(MultiConj(conj))
          } else {
            None
          }
        }
        case MultiEquality(members) if structTy.isInstanceOf[TypeVariable] => {
          val (vars, other) = members.partition(_.isInstanceOf[TypeVariable]);
          if (vars.contains(structTy) && !other.isEmpty) {
            val newProjecKind = ProjectableKind(other.head, fieldTy, fieldIndex);
            Some(MultiConj(List(c, newProjecKind)))
          } else {
            None
          }
        }
        case _ => None
      }
    }
  }

  case class BuilderKind(builderTy: Type, mergeTy: Type, resultTy: Type, argTy: Type) extends TypeConstraint {
    override type Self = BuilderKind;
    override def describe: String =
      s"${builderTy.render}≼builder(merge=${mergeTy.render}, result=${resultTy.render}, newarg=${argTy.render})";
    override def substitute(assignments: Map[Int, Type]): Option[Self] = {
      val btO = substituteType(builderTy, assignments);
      val mtO = substituteType(mergeTy, assignments);
      val rtO = substituteType(resultTy, assignments);
      val atO = substituteType(argTy, assignments);
      if (btO.isEmpty && mtO.isEmpty && rtO.isEmpty && atO.isEmpty) {
        None
      } else {
        val newBT = btO.getOrElse(builderTy);
        val newMT = mtO.getOrElse(mergeTy);
        val newRT = rtO.getOrElse(resultTy);
        val newAT = atO.getOrElse(argTy);
        Some(BuilderKind(newBT, newMT, newRT, newAT))
      }
    }

    override def normalise(): Option[TypeConstraint] = {
      builderTy match {
        case b: BuilderType => {
          var conj = List.empty[TypeConstraint];
          conj ::= MultiEquality(List(b.mergeType, mergeTy));
          conj ::= MultiEquality(List(b.resultType, resultTy));
          conj ::= MultiEquality(List(b.argType, argTy));
          Some(MultiConj(conj))
        }
        case Struct(args) => {
          var conj = List.empty[TypeConstraint];
          val mergeTypes = args.map(_ => Types.unknown);
          val resultTypes = args.map(_ => Types.unknown);
          val argTypes = args.map(_ => Types.unknown);
          args.zipWithIndex.foreach {
            case (builder, index) => {
              conj ::= BuilderKind(builder, mergeTypes(index), resultTypes(index), argTypes(index));
            }
          }
          conj ::= MultiEquality(List(Struct(mergeTypes), mergeTy));
          conj ::= MultiEquality(List(Struct(resultTypes), resultTy));
          conj ::= MultiEquality(List(Struct(argTypes), argTy));
          Some(MultiConj(conj))
        }
        case tv: TypeVariable => None // may be solved later
        case _                => None // won't be solved but leave like this for useful error reporting
      }
    }

    override def variables(): List[TypeVariable] = builderTy match {
      case tv: TypeVariable => List(tv)
      case _                => List.empty
    }

    override def merge(c: TypeConstraint): Option[TypeConstraint] = {
      c match {
        case BuilderKind(otherBuilderTy, otherMergeTy, otherResultTy, otherArgTy) if otherBuilderTy == this.builderTy => {
          var conj = List.empty[TypeConstraint];
          conj ::= this;
          conj ::= MultiEquality(List(otherMergeTy, mergeTy));
          conj ::= MultiEquality(List(otherResultTy, resultTy));
          conj ::= MultiEquality(List(otherArgTy, resultTy));
          Some(MultiConj(conj))
        }
        case MultiEquality(members) if this.builderTy.isInstanceOf[TypeVariable] => {
          val (vars, other) = members.partition(_.isInstanceOf[TypeVariable]);
          if (vars.contains(this.builderTy) && !other.isEmpty) {
            val newBuilder = BuilderKind(other.head, mergeTy, resultTy, argTy);
            Some(MultiConj(List(c, newBuilder)))
          } else {
            None
          }
        }
        case _ => None
      }
    }
  }

  case class IterableKind(dataTy: Type, elemTy: Type) extends TypeConstraint {
    override type Self = IterableKind;
    override def describe: String =
      s"${dataTy.render}≼iterable(elem=${elemTy.render})";
    override def substitute(assignments: Map[Int, Type]): Option[Self] = {
      val dtO = substituteType(dataTy, assignments);
      val etO = substituteType(elemTy, assignments);
      if (dtO.isEmpty && etO.isEmpty) {
        None
      } else {
        val newDT = dtO.getOrElse(dataTy);
        val newET = etO.getOrElse(elemTy);
        Some(IterableKind(newDT, newET))
      }
    }

    override def normalise(): Option[TypeConstraint] = {
      dataTy match {
        case Vec(t) => {
          Some(MultiEquality(List(t, elemTy)))
        }
        case Stream(t) => {
          Some(MultiEquality(List(t, elemTy)))
        }
        case tv: TypeVariable => None // may be solved later
        case _                => None // won't be solved but leave like this for useful error reporting
      }
    }

    override def variables(): List[TypeVariable] = dataTy match {
      case tv: TypeVariable => List(tv)
      case _                => List.empty
    }

    override def merge(c: TypeConstraint): Option[TypeConstraint] = {
      c match {
        case IterableKind(otherDataTy, otherElemTy) if otherDataTy == this.dataTy => {
          var conj = List.empty[TypeConstraint];
          conj ::= this;
          conj ::= MultiEquality(List(otherElemTy, elemTy));
          Some(MultiConj(conj))
        }
        case MultiEquality(members) if this.dataTy.isInstanceOf[TypeVariable] => {
          val (vars, other) = members.partition(_.isInstanceOf[TypeVariable]);
          if (vars.contains(this.dataTy) && !other.isEmpty) {
            val newIterKind = IterableKind(other.head, elemTy);
            Some(MultiConj(List(c, newIterKind)))
          } else {
            None
          }
        }
        case _ => None
      }
    }
  }

  case class MultiEquality(members: List[Type]) extends TypeConstraint {
    override type Self = MultiEquality;
    override def describe: String = members.map(_.render).mkString("=");

    override def substitute(assignments: Map[Int, Type]): Option[Self] = {
      val newMembers = members.map(substituteType(_, assignments));
      if (newMembers.forall(_.isEmpty)) {
        None
      } else {
        val newMembersFull = members.zip(newMembers).map {
          case (_, Some(ty)) => ty
          case (oldTy, None) => oldTy
        };
        Some(MultiEquality(newMembersFull))
      }
    }
    override def normalise(): Option[TypeConstraint] = {
      val newMembers = members.toSet.toList; // remove duplicates
      if (newMembers.size == 1) {
        Some(Tautology)
      } else {
        val (vars, other) = newMembers.partition(_.isInstanceOf[TypeVariable]);
        //println(s"Normalising meq: ${this.describe}\nvars=${vars.map(_.render).mkString("=")} and other=${other.map(_.render).mkString("=")}");
        if (other.isEmpty || other.size == 1) {
          if (newMembers.size == members.size) {
            None
          } else {
            Some(MultiEquality(newMembers))
          }
        } else {
          import Builders._;
          var conj = List.empty[TypeConstraint];
          val head :: rest = other;
          var unsatisfiable = false;
          rest.foreach(c => (head, c) match {
            case (Vec(eTy1), Vec(eTy2))       => conj ::= MultiEquality(List(eTy1, eTy2))
            case (Stream(eTy1), Stream(eTy2)) => conj ::= MultiEquality(List(eTy1, eTy2))
            case (Simd(eTy1), Simd(eTy2))     => conj ::= MultiEquality(List(eTy1, eTy2))
            case (Dict(keyTy1, valueTy1), Dict(keyTy2, valueTy2)) => {
              conj ::= MultiEquality(List(keyTy1, keyTy2))
              conj ::= MultiEquality(List(valueTy1, valueTy2))
            }
            case (Appender(eTy1, _), Appender(eTy2, _))                                   => conj ::= MultiEquality(List(eTy1, eTy2))
            case (StreamAppender(eTy1, _), StreamAppender(eTy2, _))                       => conj ::= MultiEquality(List(eTy1, eTy2))
            case (Merger(eTy1, opTy1, _), Merger(eTy2, opTy2, _)) if opTy1 == opTy2       => conj ::= MultiEquality(List(eTy1, eTy2)) // no inference for opTypes
            case (VecMerger(eTy1, opTy1, _), VecMerger(eTy2, opTy2, _)) if opTy1 == opTy2 => conj ::= MultiEquality(List(eTy1, eTy2)) // no inference for opTypes
            case (DictMerger(keyTy1, valueTy1, opTy1, _), DictMerger(keyTy2, valueTy2, opTy2, _)) if opTy1 == opTy2 => { // no inference for opTypes
              conj ::= MultiEquality(List(keyTy1, keyTy2))
              conj ::= MultiEquality(List(valueTy1, valueTy2))
            }
            case (GroupMerger(keyTy1, valueTy1, _), GroupMerger(keyTy2, valueTy2, _)) => {
              conj ::= MultiEquality(List(keyTy1, keyTy2))
              conj ::= MultiEquality(List(valueTy1, valueTy2))
            }
            case (Struct(elemTys1), Struct(elemTys2)) if elemTys1.size == elemTys2.size => {
              elemTys1.zip(elemTys2).foreach {
                case (eTy1, eTy2) => conj ::= MultiEquality(List(eTy1, eTy2))
              }
            }
            case (Function(params1, returnTy1), Function(params2, returnTy2)) if params1.size == params2.size => {
              params1.zip(params2).foreach {
                case (eTy1, eTy2) => conj ::= MultiEquality(List(eTy1, eTy2))
              }
              conj ::= MultiEquality(List(returnTy1, returnTy2))
            }
            case (t1, t2) => conj ::= {
              unsatisfiable = true;
              MultiEquality(List(t1, t2)); // could mark as impossible, but this gives better error reporting
            }
          });
          if (!vars.isEmpty) {
            conj ::= MultiEquality(head :: vars); // assign all type variables to one instance
            Some(MultiConj(conj))
          } else if (unsatisfiable) { // there are no variables and there was an unsatisfiable case
            None
          } else {
            Some(MultiConj(conj))
          }
        }
      }
    }

    override def variables(): List[TypeVariable] = members.flatMap {
      case tv: TypeVariable => Some(tv)
      case _                => None
    }

    override def merge(c: TypeConstraint): Option[TypeConstraint] = {
      c match {
        case MultiEquality(otherMembers) => {
          val (vars, others) = this.members.partition(_.isInstanceOf[TypeVariable]);
          val (otherVars, otherOthers) = otherMembers.partition(_.isInstanceOf[TypeVariable]);
          val varSet = vars.toSet;
          val otherVarSet = otherVars.toSet;
          if (!varSet.intersect(otherVarSet).isEmpty) {
            val newVars = varSet.union(otherVarSet).toList;
            val newMembers = newVars ++ others ++ otherOthers;
            Some(MultiEquality(newMembers))
          } else {
            None
          }
        }
        case bk: BuilderKind  => bk.merge(this)
        case ik: IterableKind => ik.merge(this)
        case _                => None
      }
    }

    def isResolved: Boolean = {
      val comp = this.completeTypes();
      val vars = this.variables();
      (comp.size == 1) && ((comp.size + vars.size) == members.size)
    }

    def resolvedType: Option[Type] = {
      this.completeTypes() match {
        case head :: Nil => Some(head)
        case _           => None
      }
    }

    private def completeTypes(): List[Type] = {
      members.filter(_.isComplete)
    }
  }

  case object Tautology extends TypeConstraint {
    override type Self = Tautology.type;
    override def describe: String = "true";
    override def substitute(assignments: Map[Int, Type]): Option[Self] = None;
    override def normalise(): Option[TypeConstraint] = None;
    override def variables(): List[TypeVariable] = List.empty;
    override def merge(c: TypeConstraint): Option[TypeConstraint] = None;
  }

  //  case object Impossibility extends TypeConstraint {
  //    override type Self = Impossibility.type;
  //    override def describe: String = "false";
  //    override def substitute(assignments: Map[Int, Type]): Option[Self] = None;
  //    override def normalise(): Option[TypeConstraint] = None;
  //    override def variables(): List[TypeVariable] = List.empty;
  //  }

  case class MultiConj(members: List[TypeConstraint]) extends TypeConstraint {
    override type Self = MultiConj;
    override def describe: String = members.map(c => s"(${c.describe})").mkString("∧");

    override def substitute(assignments: Map[Int, Type]): Option[Self] = {
      val newMembers = members.map(_.substitute(assignments));
      if (newMembers.forall(_.isEmpty)) {
        None
      } else {
        val newMembersFull = members.zip(newMembers).map {
          case (_, Some(c)) => c
          case (oldC, None) => oldC
        };
        Some(MultiConj(newMembersFull))
      }
    }
    override def normalise(): Option[TypeConstraint] = {
      val newMembers = members.map(_.normalise());
      if (newMembers.forall(_.isEmpty)) {
        val filtered = members.filterNot(_ == Tautology);
        if (filtered.size == members.size) {
          None
        } else if (filtered.isEmpty) {
          Some(Tautology)
        } else {
          Some(MultiConj(filtered))
        }
      } else {
        val newMembersFull = members.zip(newMembers).flatMap {
          case (_, Some(c)) if c != Tautology    => Some(c)
          case (oldC, None) if oldC != Tautology => Some(oldC)
          case _                                 => None
        };
        if (newMembersFull.isEmpty) {
          Some(Tautology)
        } else {
          Some(MultiConj(newMembersFull))
        }
      }
    }

    override def variables(): List[TypeVariable] = List.empty; // could aggregate children...not sure if that makes sense

    override def merge(c: TypeConstraint): Option[TypeConstraint] = {
      c match {
        case MultiConj(otherMembers) => Some(MultiConj(this.members ++ otherMembers))
        case _                       => None
      }
    }
  }

}
