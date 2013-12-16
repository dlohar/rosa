/* Copyright 2013 EPFL, Lausanne */

package leon
package real

import java.io.{PrintWriter, File}

import purescala.Common._
import purescala.Definitions._
import purescala.Trees._
import purescala.TreeOps._
import purescala.ScalaPrinter

import xlang.Trees._

import real.Trees._
import real.TreeOps._
import VCKind._


object CompilationPhase extends LeonPhase[Program,CompilationReport] {
  val name = "Real compilation"
  val description = "compilation of real programs"

  implicit val debugSection = DebugSectionVerification

  var verbose = true
  var reporter: Reporter = null
  private def debug(msg: String): Unit = {
    if (verbose) reporter.debug(msg)
  }

  override val definedOptions: Set[LeonOptionDef] = Set(
    LeonValueOptionDef("functions", "--functions=f1:f2", "Limit verification to f1, f2,..."),
    LeonFlagOptionDef("simulation", "--simulation", "Run a simulation instead of verification"),
    LeonFlagOptionDef("z3Only", "--z3Only", "Let Z3 loose on the full constraint - at your own risk."),
    LeonValueOptionDef("z3Timeout", "--z3Timeout=1000", "Timeout for Z3 in milliseconds."),
    LeonValueOptionDef("precision", "--precision=single", "Which precision to assume of the underlying"+
      "floating-point arithmetic: single, double, doubledouble, quaddouble or all (sorted from smallest)."),
    LeonFlagOptionDef("pathError", "--pathError", "Check also the path error (default is to not check)"),
    LeonFlagOptionDef("specGen", "--specGen", "Generate specs also for functions without postconditions")
  )

  def run(ctx: LeonContext)(program: Program): CompilationReport = { 
    reporter = ctx.reporter
    reporter.info("Running Compilation phase")

    var fncNamesToAnalyse = Set[String]()
    var options = RealOptions()

    for (opt <- ctx.options) opt match {
      case LeonValueOption("functions", ListValue(fs)) => fncNamesToAnalyse = Set() ++ fs
      case LeonFlagOption("simulation", v) => options = options.copy(simulation = v)
      case LeonFlagOption("z3Only", v) => options = options.copy(z3Only = v)
      case LeonFlagOption("pathError", v) => options = options.copy(pathError = v)
      case LeonFlagOption("specGen", v) => options = options.copy(specGen = v)
      case LeonValueOption("z3Timeout", ListValue(tm)) => options = options.copy(z3Timeout = tm.head.toLong)
      case LeonValueOption("precision", ListValue(ps)) => options = options.copy(precision = ps.flatMap {
        case "single" => List(Float32)
        case "double" => List(Float64)
        case "doubledouble" => List(DoubleDouble)
        case "quaddouble" => List(QuadDouble)
        case "all" => List(FPPrecision(8), FPPrecision(16), FPPrecision(32), FPPrecision(64), Float32, Float64, DoubleDouble, QuadDouble)
        case x => List(FPPrecision(x.toInt))
      }.toList)
      case _ =>
    }

    println("options: " + options)

    val fncsToAnalyse  = 
      if(fncNamesToAnalyse.isEmpty) program.definedFunctions
      else {
        val toAnalyze = program.definedFunctions.filter(f => fncNamesToAnalyse.contains(f.id.name))
        val notFound = fncNamesToAnalyse -- toAnalyze.map(fncDef => fncDef.id.name).toSet
        notFound.foreach(fn => reporter.error("Did not find function \"" + fn + "\" though it was marked for analysis."))
        toAnalyze
      }
        
    val (vcs, fncs) = analyzeThis(fncsToAnalyse, options.precision)
    if (reporter.errorCount > 0) throw LeonFatalError()
    
    reporter.info("--- Analysis complete ---")
    reporter.info("")
    if (options.simulation) {
      val simulator = new Simulator(ctx, options, program, reporter)
      val prec = if (options.precision.size == 1) options.precision.head else Float64
      for(vc <- vcs) simulator.simulateThis(vc, prec)
      new CompilationReport(List(), prec)
    } else {
      val prover = new Prover(ctx, options, program, fncs, verbose)
      
      val (finalPrecision, success) = prover.check(vcs)
      if (success) {
        val codeGenerator = new CodeGenerator(reporter, ctx, options, program, finalPrecision)
        val newProgram = codeGenerator.specToCode(program.id, program.mainObject.id, vcs) 
        val newProgramAsString = ScalaPrinter(newProgram)
        reporter.info("Generated program with %d lines.".format(newProgramAsString.lines.length))
        //reporter.info(newProgramAsString)

        val writer = new PrintWriter(new File("generated/" + newProgram.mainObject.id +".scala"))
        writer.write(newProgramAsString)
        writer.close()
      }
      else {// verification did not succeed for any precision
        reporter.warning("Could not find data type that works for all methods.")
      }
      
      new CompilationReport(vcs.sortWith((vc1, vc2) => vc1.fncId < vc2.fncId), finalPrecision)
    }
    
  }

  

  private def analyzeThis(sortedFncs: Seq[FunDef], precisions: List[Precision]): (Seq[VerificationCondition], Map[FunDef, Fnc]) = {
    var vcs = Seq[VerificationCondition]()
    var fncs = Map[FunDef, Fnc]()
    
    for (funDef <- sortedFncs if (funDef.body.isDefined)) {
      reporter.info("Analysing fnc:  %s".format(funDef.id.name))
      debug ("original fnc body: " + funDef.body.get)

      funDef.precondition.map(pre => (pre, VariablePool(pre, funDef.returnType)) ).filter(p => p._2.hasValidInput(funDef.args)).map ({
        case (pre, variables) => {
          debug ("precondition is acceptable")
          val allFncCalls = functionCallsOf(funDef.body.get).map(invc => invc.funDef.id.toString)

          // Add default roundoff on inputs
          val precondition = And(pre, And(variables.inputsWithoutNoise.map(i => Roundoff(i))))
          debug ("precondition: " + precondition)

          val resFresh = variables.resId
          val bodyWithRes = convertLetsToEquals(addResult(resFresh, funDef.body.get))
          val bodyWORes = convertLetsToEquals(funDef.body.get)
              
          
          funDef.postcondition match {
            //Option[(Identifier, Expr)]
            // TODO: invariants (got broken because of missing ResultVariable)
            /*case Some((ResultVariable()) =>
              val posts = getInvariantCondition(funDef.body.get)
              val bodyWOLets = convertLetsToEquals(funDef.body.get)
              val body = replace(posts.map(p => (p, True)).toMap, bodyWOLets)
              (body, body, Or(posts))*/

            case Some((resId, postExpr)) =>
              val postcondition = replace(Map(Variable(resId) -> Variable(resFresh)), postExpr)

              val vcBody = new VerificationCondition(funDef, Postcondition, precondition, bodyWithRes, postcondition, resFresh, 
                allFncCalls, variables, precisions)

              val assertionCollector = new AssertionCollector(funDef, precondition, variables, precisions)
              assertionCollector.transform(bodyWithRes)
          
              // for function inlining
              (assertionCollector.vcs :+ vcBody, Fnc(precondition, bodyWORes, postcondition))

            case None => // only want to generate specs
              val vcBody = new VerificationCondition(funDef, SpecGen, precondition, bodyWithRes, True, resFresh, 
                allFncCalls, variables, precisions)

              (Seq(vcBody), Fnc(precondition, bodyWORes, True))

          }
        }
      }).foreach({
        case (conds, fnc) =>
          vcs ++= conds
          fncs += ((funDef -> fnc))
        })
    }
    (vcs.sortWith((vc1, vc2) => lt(vc1, vc2)), fncs)
  }

  private def lt(vc1: VerificationCondition, vc2: VerificationCondition): Boolean = {
    if (vc1.allFncCalls.isEmpty) true
    else if (vc1.allFncCalls.contains(vc2.fncId)) false
    else true
  }

  // can return several, as we may have an if-statement
  private def getInvariantCondition(expr: Expr): List[Expr] = expr match {
    case IfExpr(cond, thenn, elze) => getInvariantCondition(thenn) ++ getInvariantCondition(elze)
    case Let(binder, value, body) => getInvariantCondition(body)
    case LessEquals(_, _) | LessThan(_, _) | GreaterThan(_, _) | GreaterEquals(_, _) => List(expr)
    case Equals(_, _) => List(expr)
    case _ =>
      println("!!! Expected invariant, but found: " + expr.getClass)
      List(BooleanLiteral(false))
  }

  // Has to run before we removed the lets!
  // Basically the first free expression that is not an if or a let is the result
  private def addResult(resId: Identifier, expr: Expr): Expr = expr match {
    case ifThen @ IfExpr(_, _, _) => Equals(Variable(resId), ifThen)
    case Let(binder, value, body) => Let(binder, value, addResult(resId, body))
    case UMinusR(_) | PlusR(_, _) | MinusR(_, _) | TimesR(_, _) | DivisionR(_, _) | SqrtR(_) | FunctionInvocation(_, _) | Variable(_) =>
      Equals(Variable(resId), expr)
    case Tuple(_) => Equals(Variable(resId), expr)
    case Block(exprs, last) => Block(exprs, addResult(resId, last))
    case _ => expr
  }

  private def convertLetsToEquals(expr: Expr): Expr = expr match {
    case Equals(l, r) => Equals(l, convertLetsToEquals(r))
    case IfExpr(cond, thenn, elze) =>
      IfExpr(cond, convertLetsToEquals(thenn), convertLetsToEquals(elze))

    case Let(binder, value, body) =>
      And(Equals(Variable(binder), convertLetsToEquals(value)), convertLetsToEquals(body))

    case Block(exprs, last) =>
      And(exprs.map(e => convertLetsToEquals(e)) :+ convertLetsToEquals(last))

    case _ => expr
  }
  /*
  class AssertionRemover extends TransformerWithPC {
    type C = Seq[Expr]
    val initC = Nil

    def register(e: Expr, path: C) = path :+ e

    override def rec(e: Expr, path: C) = e match {
      case Assertion(expr) => True
      case _ =>
        super.rec(e, path)
    }
  }*/
}