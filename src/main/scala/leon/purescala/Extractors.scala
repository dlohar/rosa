package leon
package purescala

import Trees._

object Extractors {
  import Common._
  import TypeTrees._
  import Definitions._
  import Extractors._
  import TreeOps._

  object UnaryOperator {
    def unapply(expr: Expr) : Option[(Expr,(Expr)=>Expr)] = expr match {
      case Not(t) => Some((t,Not(_)))
      case UMinus(t) => Some((t,UMinus))
      case SetCardinality(t) => Some((t,SetCardinality))
      case MultisetCardinality(t) => Some((t,MultisetCardinality))
      case MultisetToSet(t) => Some((t,MultisetToSet))
      case Car(t) => Some((t,Car))
      case Cdr(t) => Some((t,Cdr))
      case SetMin(s) => Some((s,SetMin))
      case SetMax(s) => Some((s,SetMax))
      case CaseClassSelector(cd, e, sel) => Some((e, CaseClassSelector(cd, _, sel)))
      case CaseClassInstanceOf(cd, e) => Some((e, CaseClassInstanceOf(cd, _)))
      case Assignment(id, e) => Some((e, Assignment(id, _)))
      case TupleSelect(t, i) => Some((t, TupleSelect(_, i)))
      case ArrayLength(a) => Some((a, ArrayLength))
      case ArrayClone(a) => Some((a, ArrayClone))
      case ArrayMake(t) => Some((t, ArrayMake))
      case Waypoint(i, t) => Some((t, (expr: Expr) => Waypoint(i, expr)))
      case e@Epsilon(t) => Some((t, (expr: Expr) => Epsilon(expr).setType(e.getType).setPosInfo(e)))
      case ue: UnaryExtractable => ue.extract
      case _ => None
    }
  }

  trait UnaryExtractable {
    def extract: Option[(Expr, (Expr)=>Expr)];
  }

  object BinaryOperator {
    def unapply(expr: Expr) : Option[(Expr,Expr,(Expr,Expr)=>Expr)] = expr match {
      case Equals(t1,t2) => Some((t1,t2,Equals.apply))
      case Iff(t1,t2) => Some((t1,t2,Iff(_,_)))
      case Implies(t1,t2) => Some((t1,t2,Implies.apply))
      case Plus(t1,t2) => Some((t1,t2,Plus))
      case Minus(t1,t2) => Some((t1,t2,Minus))
      case Times(t1,t2) => Some((t1,t2,Times))
      case Division(t1,t2) => Some((t1,t2,Division))
      case Modulo(t1,t2) => Some((t1,t2,Modulo))
      case LessThan(t1,t2) => Some((t1,t2,LessThan))
      case GreaterThan(t1,t2) => Some((t1,t2,GreaterThan))
      case LessEquals(t1,t2) => Some((t1,t2,LessEquals))
      case GreaterEquals(t1,t2) => Some((t1,t2,GreaterEquals))
      case ElementOfSet(t1,t2) => Some((t1,t2,ElementOfSet))
      case SubsetOf(t1,t2) => Some((t1,t2,SubsetOf))
      case SetIntersection(t1,t2) => Some((t1,t2,SetIntersection))
      case SetUnion(t1,t2) => Some((t1,t2,SetUnion))
      case SetDifference(t1,t2) => Some((t1,t2,SetDifference))
      case Multiplicity(t1,t2) => Some((t1,t2,Multiplicity))
      case SubmultisetOf(t1,t2) => Some((t1,t2,SubmultisetOf))
      case MultisetIntersection(t1,t2) => Some((t1,t2,MultisetIntersection))
      case MultisetUnion(t1,t2) => Some((t1,t2,MultisetUnion))
      case MultisetPlus(t1,t2) => Some((t1,t2,MultisetPlus))
      case MultisetDifference(t1,t2) => Some((t1,t2,MultisetDifference))
      case SingletonMap(t1,t2) => Some((t1,t2,SingletonMap))
      case mg@MapGet(t1,t2) => Some((t1,t2, (t1, t2) => MapGet(t1, t2).setPosInfo(mg)))
      case MapUnion(t1,t2) => Some((t1,t2,MapUnion))
      case MapDifference(t1,t2) => Some((t1,t2,MapDifference))
      case MapIsDefinedAt(t1,t2) => Some((t1,t2, MapIsDefinedAt))
      case ArrayFill(t1, t2) => Some((t1, t2, ArrayFill))
      case ArraySelect(t1, t2) => Some((t1, t2, ArraySelect))
      case Concat(t1,t2) => Some((t1,t2,Concat))
      case ListAt(t1,t2) => Some((t1,t2,ListAt))
      case Let(binders, e, body) => Some((e, body, (e: Expr, b: Expr) => Let(binders, e, body)))
      case LetDef(fd, body) => 
        fd.body match {
          case Some(b) =>
            Some((b, body, (b: Expr, body: Expr) => {
              val nfd = new FunDef(fd.id, fd.returnType, fd.args)
              nfd.body = Some(b)

              LetDef(nfd, body)
            }))
          case _ =>
            None
        }
      case LetTuple(binders, e, body) => Some((e, body, (e: Expr, b: Expr) => LetTuple(binders, e, body)))
      case wh@While(t1, t2) => Some((t1,t2, (t1, t2) => While(t1, t2).setInvariant(wh.invariant).setPosInfo(wh)))
      case ex: BinaryExtractable => ex.extract
      case _ => None
    }
  }

  trait BinaryExtractable {
    def extract: Option[(Expr, Expr, (Expr, Expr)=>Expr)];
  }

  object NAryOperator {
    def unapply(expr: Expr) : Option[(Seq[Expr],(Seq[Expr])=>Expr)] = expr match {
      case fi @ FunctionInvocation(fd, args) => Some((args, (as => FunctionInvocation(fd, as).setPosInfo(fi))))
      case AnonymousFunctionInvocation(id, args) => Some((args, (as => AnonymousFunctionInvocation(id, as))))
      case CaseClass(cd, args) => Some((args, CaseClass(cd, _)))
      case And(args) => Some((args, And.apply))
      case Or(args) => Some((args, Or.apply))
      case FiniteSet(args) => Some((args, FiniteSet))
      case FiniteMap(args) => Some((args, (as : Seq[Expr]) => FiniteMap(as.asInstanceOf[Seq[SingletonMap]])))
      case FiniteMultiset(args) => Some((args, FiniteMultiset))
      case ArrayUpdate(t1, t2, t3) => Some((Seq(t1,t2,t3), (as: Seq[Expr]) => ArrayUpdate(as(0), as(1), as(2))))
      case ArrayUpdated(t1, t2, t3) => Some((Seq(t1,t2,t3), (as: Seq[Expr]) => ArrayUpdated(as(0), as(1), as(2))))
      case FiniteArray(args) => Some((args, FiniteArray))
      case Distinct(args) => Some((args, Distinct))
      case Block(args, rest) => Some((args :+ rest, exprs => Block(exprs.init, exprs.last)))
      case Tuple(args) => Some((args, Tuple))
      case IfExpr(cond, then, elze) => Some((Seq(cond, then, elze), (as: Seq[Expr]) => IfExpr(as(0), as(1), as(2))))
      case MatchExpr(scrut, cases) =>
        Some((scrut +: cases.flatMap{ case SimpleCase(_, e) => Seq(e)
                                     case GuardedCase(_, e1, e2) => Seq(e1, e2) }
             , { es: Seq[Expr] =>
            var i = 1;
            val newcases = for (caze <- cases) yield caze match {
              case SimpleCase(b, _) => i+=1; SimpleCase(b, es(i-1)) 
              case GuardedCase(b, _, _) => i+=2; GuardedCase(b, es(i-2), es(i-1)) 
            }

           MatchExpr(es(0), newcases)
           }))
      case ex: NAryExtractable => ex.extract
      case _ => None
    }
  }

  trait NAryExtractable {
    def extract: Option[(Seq[Expr], (Seq[Expr])=>Expr)];
  }

  object SimplePatternMatching {
    def isSimple(me: MatchExpr) : Boolean = unapply(me).isDefined

    // (scrutinee, classtype, list((caseclassdef, variable, list(variable), rhs)))
    def unapply(e: MatchExpr) : Option[(Expr,ClassType,Seq[(CaseClassDef,Identifier,Seq[Identifier],Expr)])] = {
      val MatchExpr(scrutinee, cases) = e
      val sType = scrutinee.getType

      if(sType.isInstanceOf[TupleType]) {
        None
      } else if(sType.isInstanceOf[AbstractClassType]) {
        val cCD = sType.asInstanceOf[AbstractClassType].classDef
        if(cases.size == cCD.knownChildren.size && cases.forall(!_.hasGuard)) {
          var seen = Set.empty[ClassTypeDef]
          
          var lle : List[(CaseClassDef,Identifier,List[Identifier],Expr)] = Nil
          for(cse <- cases) {
            cse match {
              case SimpleCase(CaseClassPattern(binder, ccd, subPats), rhs) if subPats.forall(_.isInstanceOf[WildcardPattern]) => {
                seen = seen + ccd

                val patID : Identifier = if(binder.isDefined) {
                  binder.get
                } else {
                  FreshIdentifier("cse", true).setType(CaseClassType(ccd))
                }

                val argIDs : List[Identifier] = (ccd.fields zip subPats.map(_.asInstanceOf[WildcardPattern])).map(p => if(p._2.binder.isDefined) {
                  p._2.binder.get
                } else {
                  FreshIdentifier("pat", true).setType(p._1.tpe)
                }).toList

                lle = (ccd, patID, argIDs, rhs) :: lle
              }
              case _ => ;
            }
          }
          lle = lle.reverse

          if(seen.size == cases.size) {
            Some((scrutinee, sType.asInstanceOf[AbstractClassType], lle))
          } else {
            None
          }
        } else {
          None
        }
      } else {
        val cCD = sType.asInstanceOf[CaseClassType].classDef
        if(cases.size == 1 && !cases(0).hasGuard) {
          val SimpleCase(pat,rhs) = cases(0).asInstanceOf[SimpleCase]
          pat match {
            case CaseClassPattern(binder, ccd, subPats) if (ccd == cCD && subPats.forall(_.isInstanceOf[WildcardPattern])) => {
              val patID : Identifier = if(binder.isDefined) {
                binder.get
              } else {
                FreshIdentifier("cse", true).setType(CaseClassType(ccd))
              }

              val argIDs : List[Identifier] = (ccd.fields zip subPats.map(_.asInstanceOf[WildcardPattern])).map(p => if(p._2.binder.isDefined) {
                p._2.binder.get
              } else {
                FreshIdentifier("pat", true).setType(p._1.tpe)
              }).toList

              Some((scrutinee, CaseClassType(cCD), List((cCD, patID, argIDs, rhs))))
            }
            case _ => None
          }
        } else {
          None
        }
      }
    }
  }

  object NotSoSimplePatternMatching {
    def coversType(tpe: ClassTypeDef, patterns: Seq[Pattern]) : Boolean = {
      if(patterns.isEmpty) {
        false
      } else if(patterns.exists(_.isInstanceOf[WildcardPattern])) {
        true
      } else {
        val allSubtypes: Seq[CaseClassDef] = tpe match {
          case acd @ AbstractClassDef(_,_) => acd.knownDescendents.filter(_.isInstanceOf[CaseClassDef]).map(_.asInstanceOf[CaseClassDef])
          case ccd: CaseClassDef => List(ccd)
        }

        var seen: Set[CaseClassDef] = Set.empty
        var secondLevel: Map[(CaseClassDef,Int),List[Pattern]] = Map.empty

        for(pat <- patterns) if (pat.isInstanceOf[CaseClassPattern]) {
          val pattern: CaseClassPattern = pat.asInstanceOf[CaseClassPattern]
          val ccd: CaseClassDef = pattern.caseClassDef
          seen = seen + ccd

          for((subPattern,i) <- (pattern.subPatterns.zipWithIndex)) {
            val seenSoFar = secondLevel.getOrElse((ccd,i), Nil)
            secondLevel = secondLevel + ((ccd,i) -> (subPattern :: seenSoFar))
          }
        }

        allSubtypes.forall(ccd => {
          seen(ccd) && ccd.fields.zipWithIndex.forall(p => p._1.tpe match {
            case t: ClassType => coversType(t.classDef, secondLevel.getOrElse((ccd, p._2), Nil))
            case _ => true
          })
        })
      }
    }

    def unapply(pm : MatchExpr) : Option[MatchExpr] = if(!Settings.experimental) None else (pm match {
      case MatchExpr(scrutinee, cases) if cases.forall(_.isInstanceOf[SimpleCase]) => {
        val allPatterns = cases.map(_.pattern)
        Settings.reporter.info("This might be a complete pattern-matching expression:")
        Settings.reporter.info(pm)
        Settings.reporter.info("Covered? " + coversType(pm.scrutineeClassType.classDef, allPatterns))
        None
      }
      case _ => None
    })
  }

  object TopLevelOrs { // expr1 AND (expr2 AND (expr3 AND ..)) => List(expr1, expr2, expr3)
    def unapply(e: Expr): Option[Seq[Expr]] = e match {
      case Or(exprs) =>
        Some(exprs.flatMap(unapply(_).flatten))
      case e =>
        Some(Seq(e))
    }
  }
  object TopLevelAnds { // expr1 AND (expr2 AND (expr3 AND ..)) => List(expr1, expr2, expr3)
    def unapply(e: Expr): Option[Seq[Expr]] = e match {
      case And(exprs) =>
        Some(exprs.flatMap(unapply(_).flatten))
      case e =>
        Some(Seq(e))
    }
  }

}
