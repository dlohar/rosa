package leon
package numerics

import purescala.Trees._
import purescala.TypeTrees._
import purescala.TreeOps._
import purescala.Definitions._
import purescala.Common._

import affine._
import affine.XFloat._
import Rational.{zero, max, abs}

import RoundoffType._
import Precision._
//import Utils._
import VariableShop._

class XEvaluator(reporter: Reporter, solver: NumericSolver, precision: Precision, vcMap: Map[FunDef, VerificationCondition]) {
  val printStats = true
  val unitRoundoff = getUnitRoundoff(precision)
  val unitRoundoffDefault = getUnitRoundoff(Float64)


  def evaluateWithFncCalls(expr: List[Expr], precondition: Expr, inputs: Map[Variable, Record]): (Map[Expr, XFloat], Map[Int, Expr]) = {
    //println("Evaluating: " + expr)
    val config = XFloatConfig(reporter, solver, precondition, precision, unitRoundoff)
    val (variables, indices) = variables2xfloats(inputs, config)
    solver.clearCounts
    val values = inXFloatsWithFncs(expr, variables, config) -- inputs.keys
    if (printStats) reporter.info("STAAATS: " + solver.getCounts)
    (values, indices)
  }

  private def inXFloatsWithFncs(exprs: List[Expr], vars: Map[Expr, XFloat], config: XFloatConfig): Map[Expr, XFloat] = {
    var currentVars: Map[Expr, XFloat] = vars

    for (expr <- exprs) expr match {
      case Equals(variable, value) =>
        try {
          val computedValue = evalWithFncs(value, currentVars, config)
          currentVars = currentVars + (variable -> computedValue)
          //println("Computed for: " + variable + "  : " + computedValue)
          //println("tree: " + computedValue.tree)
        } catch {
          case UnsupportedFragmentException(msg) => reporter.error(msg)
        }

      case BooleanLiteral(true) => ;
      case _ =>
        reporter.error("AA cannot handle: " + expr)
    }

    currentVars
  }

  private def evalWithFncs(expr: Expr, vars: Map[Expr, XFloat], config: XFloatConfig): XFloat = expr match {
    case v @ Variable(id) => vars(v)
    case RationalLiteral(v) => XFloat(v, config)
    case IntLiteral(v) => XFloat(v, config)
    case UMinus(rhs) => - evalWithFncs(rhs, vars, config)
    case Plus(lhs, rhs) => evalWithFncs(lhs, vars, config) + evalWithFncs(rhs, vars, config)
    case Minus(lhs, rhs) => evalWithFncs(lhs, vars, config) - evalWithFncs(rhs, vars, config)
    case Times(lhs, rhs) => evalWithFncs(lhs, vars, config) * evalWithFncs(rhs, vars, config)
    case Division(lhs, rhs) => evalWithFncs(lhs, vars, config) / evalWithFncs(rhs, vars, config)
    case Sqrt(t) => evalWithFncs(t, vars, config).squareRoot

    case FunctionInvocation(funDef, args) =>
      //println("function call: " + funDef.id.toString)
      val fresh = getNewFncVariable(funDef.id.name)
      val arguments: Map[Expr, Expr] = funDef.args.map(decl => decl.toVariable).zip(args).toMap
      val newBody = replace(arguments, vcMap(funDef).body)
      //println("newBody: " + newBody)
      //println("inputs: ")
      //for((k, v) <- vars) {
        //println(k + ": " +v.tree)
        //println("compacted: " + compactXFloat(v, k).tree)
      //}
      val bodyAsList = newBody match {
        case And(list) => list
        case eq: Equals => List(eq)
        // e.g. if the function has if-then-else's
        case _=> throw UnsupportedFragmentException("AA cannot handle: " + expr); null
      }

      //println("bodyList: " + bodyAsList)
      val vals = inXFloatsWithFncs(bodyAsList.toList, vars, config)
      val result = vals(ResultVariable())
      //println("result: " + result)
      val newXFloat = compactXFloat(result, fresh)
      //println("newXFloat: " + newXFloat)
      newXFloat
      
    case _ =>
      throw UnsupportedFragmentException("AA cannot handle: " + expr)
      null
  }



  private def rangeConstraint(v: Expr, i: RationalInterval): Expr = {
    And(LessEquals(RationalLiteral(i.xlo), v), LessEquals(v, RationalLiteral(i.xhi)))
  }

  def evaluateWithMerging(expr: Expr, precondition: Expr, inputs: Map[Variable, Record]): (Map[Expr, XFloat], Map[Int, Expr]) = {
    val config = XFloatConfig(reporter, solver, precondition, precision, unitRoundoff)
    val (variables, indices) = variables2xfloats(inputs, config)
    solver.clearCounts
    val values = inXFloatsWithMerging(reporter, expr, variables, config)._1 -- inputs.keys
    if (printStats) reporter.info("STAAATS: " + solver.getCounts)
    (values, indices)
  }

  private def inXFloatsWithMerging(reporter: Reporter, expr: Expr, vars: Map[Expr, XFloat],
    config: XFloatConfig): (Map[Expr, XFloat], Option[XFloat]) = {
    //println("\nEvaluating: " + expr)
    //println("with Map: " + vars)
    //println("with pre: " + config.getCondition)
    expr match {
      case And(args) =>
        var currentVars: Map[Expr, XFloat] = vars
        for (arg <- args) {
          val (map, xf) = inXFloatsWithMerging(reporter, arg, currentVars, config)
          currentVars = map
        }
        //println("currentVars: " + currentVars)  
        (currentVars, None)

      case Equals(variable, IfExpr(cond, then, elze)) =>
        println("Equals with branch: " + expr)
        val thenConfig = config.addCondition(cond)
        val elzeConfig = config.addCondition(negate(cond))

        val (thenMap, thenValue) = if (sanityCheck(thenConfig.getCondition)) inXFloatsWithMerging(reporter, then, vars, thenConfig)
          else (vars, None)
        val (elzeMap, elzeValue) = if (sanityCheck(elzeConfig.getCondition)) inXFloatsWithMerging(reporter, elze, vars, elzeConfig)
          else (vars, None)
        assert(!thenValue.isEmpty || !elzeValue.isEmpty)

        // When the actual computation goes a different way than the real one
        val (flCond1, reCond1) = getDiffPathsConditions(cond, vars, config)
        val (flCond2, reCond2) = getDiffPathsConditions(negate(cond), vars, config)
        println("flCond1: " + flCond1)
        println("reCond1: " + reCond1)
        println("flCond2: " + flCond2)
        println("reCond2: " + reCond2)
        
        val pathErrorThen = {
          println("\n computing path error then")
          if (sanityCheck(And(flCond1, negate(cond)))) {
            val (m, floatRange) = inXFloatsWithMerging(reporter, elze, vars, elzeConfig.addCondition(flCond1))
            println("floatRange: " + floatRange)
            
            val freshMap = getFreshMap(vars.keySet)
            val (freshThen, freshVars) = freshenUp(then, freshMap, vars)
            val (mm, realRange) = inXFloatsWithMerging(reporter, freshThen, freshVars, thenConfig.addCondition(reCond1).freshenUp(freshMap))
            println("realRange: "+realRange)
            val diff = (floatRange.get - realRange.get).interval
            val maxError = max(abs(diff.xlo), abs(diff.xhi))
            maxError
          } else Rational.zero
        }
        println("pathError1: " + pathErrorThen)

        val pathErrorElze = {
          println("\n computing path error else")
          if (sanityCheck(And(flCond2, cond))) {
            val (m, floatRange) = inXFloatsWithMerging(reporter, then, vars, thenConfig.addCondition(flCond2))
            println("floatRange: " + floatRange)

            val freshMap = getFreshMap(vars.keySet)
            val (freshElze, freshVars) = freshenUp(elze, freshMap, vars)
            val (mm, realRange) = inXFloatsWithMerging(reporter, freshElze, freshVars, elzeConfig.addCondition(reCond2).freshenUp(freshMap))
            println("realRange: "+realRange)
            //TODO: one should really remove the errors on realRange
            val diff = (floatRange.get - realRange.get).interval
            println("diff: " + diff)
            val maxError = max(abs(diff.xlo), abs(diff.xhi))
            maxError
          } else Rational.zero
        }
        println("pathError2: " + pathErrorElze)
        // TODO: do we  also have to keep track of the flRange computed? I think so

        (vars + (variable -> mergeXFloat(thenValue, elzeValue, config).get), None)

      case Equals(variable, value) =>
        val computedValue = eval(value, vars, config)
        //println("computedValue: " + computedValue)
        (vars + (variable -> computedValue), None)

      case IfExpr(cond, then, elze) =>
        // TODO: eval error across paths
        val thenConfig = config.addCondition(cond)
        val elzeConfig = config.addCondition(Not(cond))
        
        val (thenMap, thenValue) = if (sanityCheck(thenConfig.getCondition)) inXFloatsWithMerging(reporter, then, vars, thenConfig)
          else { //println("Skipping then branch");
            (vars, None)}

        val (elzeMap, elzeValue) = if (sanityCheck(elzeConfig.getCondition)) inXFloatsWithMerging(reporter, elze, vars, elzeConfig)
          else { //println("Skipping else branch");
            (vars, None)}
        //println("thenValue: " + thenValue)
        //println("elseValue: " + elzeValue)
        //println("thenMap: " + thenMap)
        //println("elzeMap: " + elzeMap)
        assert(thenValue.isEmpty && elzeValue.isEmpty)
        //println("merged: " + mergeXFloat(thenMap.get(ResultVariable()), elzeMap.get(ResultVariable()), config))

        mergeXFloat(thenMap.get(ResultVariable()), elzeMap.get(ResultVariable()), config) match {
          case Some(res) => (vars + (ResultVariable() -> res), None)
          case None => (vars, None)
        }

      case Variable(_) | RationalLiteral(_) | IntLiteral(_) | UMinus(_) | Plus(_, _) | Minus(_, _) | Times(_, _) | Division(_, _) | Sqrt(_) =>
        (vars, Some(eval(expr, vars, config)))

      case BooleanLiteral(true) => (vars, None)
      case _ =>
        reporter.error("AA cannot handle: " + expr)
        (Map.empty, None)
    }
  }

  def getFreshMap(vars: Set[Expr]): Map[Expr, Expr] = {
    vars.collect {
      case v @ Variable(id) => (v, getFreshVarOf(id.toString))
    }.toMap
  }

  def freshenUp(expr: Expr, freshMap: Map[Expr, Expr], inputs: Map[Expr, XFloat]): (Expr, Map[Expr, XFloat]) = {
    val freshExpr = replace(freshMap, expr)
    val freshInputs: Map[Expr, XFloat] = inputs.collect {
      case (k, xf) if(freshMap.contains(k)) =>
        val newXf = new XFloat(replace(freshMap, xf.tree), xf.approxInterval, xf.error, xf.config.freshenUp(freshMap))
       (freshMap(k), newXf)
    }
    (freshExpr, freshInputs)
  }

  private def getDiffPathsConditions(cond: Expr, inputs: Map[Expr, XFloat], config: XFloatConfig): (Expr, Expr) = cond match {
    case LessThan(l, r) =>
      val errLeft = eval(l, inputs, config).maxError
      val errRight = eval(r, inputs, config).maxError
      val floatCond = LessThan(l, Plus(r, RationalLiteral(errLeft + errRight)))
      val realCond = GreaterEquals(l, Minus(r, RationalLiteral(errLeft + errRight)))
      (floatCond, realCond)

    case LessEquals(l, r) =>
      val errLeft = eval(l, inputs, config).maxError
      val errRight = eval(r, inputs, config).maxError
      val floatCond = LessEquals(l, Plus(r, RationalLiteral(errLeft + errRight)))
      val realCond = GreaterThan(l, Minus(r, RationalLiteral(errLeft + errRight)))
      (floatCond, realCond)

    case GreaterThan(l, r) =>
      val errLeft = eval(l, inputs, config).maxError
      val errRight = eval(r, inputs, config).maxError
      val floatCond = GreaterThan(l, Minus(r, RationalLiteral(errLeft + errRight)))
      val realCond = LessEquals(l, Plus(r, RationalLiteral(errLeft + errRight)))
      (floatCond, realCond)

    case GreaterEquals(l, r) =>
      val errLeft = eval(l, inputs, config).maxError
      val errRight = eval(r, inputs, config).maxError
      val floatCond = GreaterEquals(l, Minus(r, RationalLiteral(errLeft + errRight)))
      val realCond = LessThan(l, Plus(r, RationalLiteral(errLeft + errRight)))
      (floatCond, realCond)
  }

  private def mergeXFloat(one: Option[XFloat], two: Option[XFloat], config: XFloatConfig): Option[XFloat] = (one, two) match {
    case (Some(x1), Some(x2)) =>
      val newInt = x1.realInterval.union(x2.realInterval)
      val newError = max(x1.maxError, x2.maxError)
      val fresh = getNewXFloatVar
      val newConfig = config.addCondition(rangeConstraint(fresh, newInt))
      Some(xFloatWithUncertain(fresh, newInt, newConfig, newError, false)._1)
    case (Some(x1), None) => Some(x1)
    case (None, Some(x2)) => Some(x2)
    case (None, None) => None
  }

  // TODO: compacting of XFloats
  def evaluate(expr: List[Expr], precondition: Expr, inputs: Map[Variable, Record]): (Map[Expr, XFloat], Map[Int, Expr]) = {
    val config = XFloatConfig(reporter, solver, precondition, precision, unitRoundoff)
    val (variables, indices) = variables2xfloats(inputs, config)
    solver.clearCounts
    val values = inXFloats(reporter, expr, variables, config) -- inputs.keys
    if (printStats) reporter.info("STAAATS: " + solver.getCounts)
    (values, indices)
  }


  private def inXFloats(reporter: Reporter, exprs: List[Expr], vars: Map[Expr, XFloat], config: XFloatConfig): Map[Expr, XFloat] = {
    var currentVars: Map[Expr, XFloat] = vars

    for (expr <- exprs) expr match {
      case Equals(variable, value) =>
        try {
          val computedValue = eval(value, currentVars, config)
          //val compacted = compactXFloat(computedValue, Variable(FreshIdentifier("x")).setType(RealType))
          currentVars = currentVars + (variable -> computedValue)
        } catch {
          case UnsupportedFragmentException(msg) => reporter.error(msg)
        }
      case BooleanLiteral(true) => ;
      case _ =>
        reporter.error("AA cannot handle: " + expr)
    }

    currentVars
  }

  // Evaluates an arithmetic expression
  private def eval(expr: Expr, vars: Map[Expr, XFloat], config: XFloatConfig): XFloat = {
    val xfloat = expr match {
    case v @ Variable(id) => vars(v)
    case RationalLiteral(v) => XFloat(v, config)
    case IntLiteral(v) => XFloat(v, config)
    case UMinus(rhs) => - eval(rhs, vars, config)
    case Plus(lhs, rhs) => eval(lhs, vars, config) + eval(rhs, vars, config)
    case Minus(lhs, rhs) => eval(lhs, vars, config) - eval(rhs, vars, config)
    case Times(lhs, rhs) => eval(lhs, vars, config) * eval(rhs, vars, config)
    case Division(lhs, rhs) => eval(lhs, vars, config) / eval(rhs, vars, config)
    case Sqrt(t) => eval(t, vars, config).squareRoot
    case _ =>
      throw UnsupportedFragmentException("AA cannot handle: " + expr)
      null
    }
    /*print(".("+formulaSize(xfloat.tree)+") ") // marking progress
    if (formulaSize(xfloat.tree) > 70) {
      println("compacting")
      val fresh = getNewXFloatVar
      compactXFloat(xfloat, fresh)
    } else {
      xfloat
    }*/
    xfloat
  }

  private def compactXFloat(xfloat: XFloat, newTree: Expr): XFloat = {
    val newConfig = xfloat.config.addCondition(rangeConstraint(newTree, xfloat.realInterval))
    val (newXFloat, index) = xFloatWithUncertain(newTree, xfloat.realInterval, newConfig, xfloat.maxError, false)
    newXFloat
  }

  private def sanityCheck(pre: Expr, silent: Boolean = false): Boolean = {
    import Sat._
    solver.checkSat(pre) match {
      case (SAT, model) =>
        if (!silent) reporter.info("Sanity check passed! :-)")
        //reporter.info("model: " + model)
        true
      case (UNSAT, model) =>
        if (!silent) reporter.warning("Not sane! " + pre)
        false
      case _ =>
        reporter.info("Sanity check failed! ")// + sanityCondition)
        false
    }
  }

    /*private def mergeXFloatMap(first: Map[Expr, XFloat], second: Map[Expr, XFloat]): Map[Expr, XFloat] = {
    val newKeys: Set[Expr] = first.keySet ++ second.keySet
    var newMap = Map[Expr, XFloat]()
    for (key <- newKeys) {
      (first.get(key), second.get(key)) match {
        case (Some(xf1), Some(xf2))
      }
    }

  }*/


  /*private def getDiffPathsConditions(cond: Expr, inputs: Map[Expr, XFloat], config: XFloatConfig): (Expr, Expr) = cond match {
    case LessThan(l, r) =>
      val errLeft = eval(l, inputs, config).maxError
      val errRight = eval(r, inputs, config).maxError
      val floatCond = LessThan(Plus(l, RationalLiteral(errLeft)), Minus(r, RationalLiteral(errRight)))
      val realCond = GreaterEquals(Minus(l, RationalLiteral(errLeft)), Plus(r, RationalLiteral(errRight)))
      (floatCond, realCond)

    case LessEquals(l, r) =>
      val errLeft = eval(l, inputs, config).maxError
      val errRight = eval(r, inputs, config).maxError
      val floatCond = LessEquals(Plus(l, RationalLiteral(errLeft)), Minus(r, RationalLiteral(errRight)))
      val realCond = GreaterThan(Minus(l, RationalLiteral(errLeft)), Plus(r, RationalLiteral(errRight)))
      (floatCond, realCond)

    case GreaterThan(l, r) =>
      val errLeft = eval(l, inputs, config).maxError
      val errRight = eval(r, inputs, config).maxError
      val floatCond = GreaterThan(Minus(l, RationalLiteral(errLeft)), Plus(r, RationalLiteral(errRight)))
      val realCond = LessEquals(Plus(l, RationalLiteral(errLeft)), Minus(r, RationalLiteral(errRight)))
      (floatCond, realCond)

    case GreaterEquals(l, r) =>
      val errLeft = eval(l, inputs, config).maxError
      val errRight = eval(r, inputs, config).maxError
      val floatCond = GreaterEquals(Minus(l, RationalLiteral(errLeft)), Plus(r, RationalLiteral(errRight)))
      val realCond = LessThan(Plus(l, RationalLiteral(errLeft)), Minus(r, RationalLiteral(errRight)))
      (floatCond, realCond)
  }*/
}