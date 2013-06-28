package leon
package numerics

import purescala.Common._
import purescala.Definitions._
import purescala.Trees._
import purescala.TreeOps._
import purescala.TypeTrees._
import Precision._
import SpecGenType._

class CodeGeneration(reporter: Reporter, precision: Precision) {
  val specTransformer = new SpecTransformer

  // Produces something that does not typecheck. But printed it should be fine.
  def specToCode(programId: Identifier, objectId: Identifier, vcs: Seq[VerificationCondition], specGenType: SpecGenType): Program = {

    var defs: Seq[Definition] = Seq.empty

    for (vc <- vcs) {
      val f = vc.funDef
      val id = f.id
      val returnType = getNonRealType(f.returnType)
      val args: Seq[VarDecl] = f.args.map(decl => VarDecl(decl.id, getNonRealType(decl.tpe)))

      val body = f.body
      val funDef = new FunDef(id, returnType, args)
      funDef.body = body

      // TODO: noise(x) does not really exist in Doubles!
      f.precondition match {
        case Some(p) => funDef.precondition = Some(specTransformer.transform(f.precondition.get))
        case None => ;
      }

      if (specGenType != None) {
        funDef.postcondition = vc.generatedPost
      } else {
        funDef.postcondition = f.postcondition
        /*f.postcondition match {
          case Some(p) => funDef.postcondition = Some(specTransformer.transform(f.postcondition.get))
          case None => ;
        }*/
      }
      defs = defs :+ funDef
    }
    val invariants: Seq[Expr] = Seq.empty

    val newProgram = Program(programId, ObjectDef(objectId, defs, invariants))
    newProgram

  }

  // TODO: this should be parametric in which float we tested
  def getNonRealType(tpe: TypeTree): TypeTree = (tpe, precision) match {
    case (RealType, Float64) => Float64Type
    case (RealType, Float32) => Float32Type
    case _ => tpe
  }

  /*
    Replaces all constructs related to Real's with something meant to compile.
  */
  class SpecTransformer extends TransformerWithPC {
    type C = Seq[Expr]
    val initC = Nil

    def register(e: Expr, path: C) = path :+ e

    override def rec(e: Expr, path: C) = e match {
      //case Noise(_, _) => BooleanLiteral(true)
      case Roundoff(expr) => BooleanLiteral(true)
      case _ =>
        super.rec(e, path)
    }

  }

}
