import cp.Definitions._

object FindAllCalls { 
  @spec sealed abstract class Color
  @spec case class Red() extends Color
  @spec case class Black() extends Color
 
  @spec sealed abstract class Tree
  @spec case class Empty() extends Tree
  @spec case class Node(color: Color, left: Tree, value: Int, right: Tree) extends Tree

  @spec sealed abstract class OptionInt
  @spec case class Some(v : Int) extends OptionInt
  @spec case class None() extends OptionInt

  @spec def size(t: Tree) : Int = (t match {
    case Empty() => 0
    case Node(_, l, v, r) => size(l) + 1 + size(r)
  }) ensuring (_ >= 0)

  @spec def isBlack(t: Tree) : Boolean = t match {
    case Empty() => true
    case Node(Black(),_,_,_) => true
    case _ => false
  }

  @spec def redNodesHaveBlackChildren(t: Tree) : Boolean = t match {
    case Empty() => true
    case Node(Black(), l, _, r) => redNodesHaveBlackChildren(l) && redNodesHaveBlackChildren(r)
    case Node(Red(), l, _, r) => isBlack(l) && isBlack(r) && redNodesHaveBlackChildren(l) && redNodesHaveBlackChildren(r)
  }

  @spec def blackBalanced(t : Tree) : Boolean = t match {
    case Node(_,l,_,r) => blackBalanced(l) && blackBalanced(r) && blackHeight(l) == blackHeight(r)
    case Empty() => true
  }

  @spec def blackHeight(t : Tree) : Int = t match {
    case Empty() => 1
    case Node(Black(), l, _, _) => blackHeight(l) + 1
    case Node(Red(), l, _, _) => blackHeight(l)
  }

  @spec def boundValues(t : Tree, bound : Int) : Boolean = t match {
    case Empty() => true
    case Node(_,l,v,r) => 0 <= v && v <= bound && boundValues(l,bound) && boundValues(r,bound)
  }

  @spec def orderedKeys(t : Tree) : Boolean = orderedKeys(t, None(), None())

  @spec def orderedKeys(t : Tree, min : OptionInt, max : OptionInt) : Boolean = t match {
    case Empty() => true
    case Node(c,a,v,b) =>
      val minOK = 
        min match {
          case Some(minVal) =>
            v > minVal
          case None() => true
        }
      val maxOK =
        max match {
          case Some(maxVal) =>
            v < maxVal
          case None() => true
        }
      minOK && maxOK && orderedKeys(a, min, Some(v)) && orderedKeys(b, Some(v), max)
  }

  @spec def isRedBlackTree(t : Tree) : Boolean = {
    isBlack(t) && blackBalanced(t) && redNodesHaveBlackChildren(t) && orderedKeys(t)
  }

  def main(args: Array[String]) : Unit = {
    val bound = if (args.isEmpty) 3 else args(0).toInt

    var counter = 1
    for (tree <- findAll((t : Tree) => isRedBlackTree(t) && boundValues(t, bound))) {
      println("Here is the tree " + counter)
      counter = counter + 1
      println(print(tree))
    }
  }

  /** Printing trees */
  def indent(s: String) = ("  "+s).split('\n').mkString("\n  ")

  def print(tree: Tree): String = tree match {
    case Node(c,l,v,r) =>
      indent(print(r)) + "\n" + (if (c == Black()) "B" else "R") + " " + v.toString + "\n" + indent(print(l))
    case Empty() => "E"
  }
}