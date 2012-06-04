package scala.slick.ast

import OptimizerUtil._
import scala.slick.util.Logging
import scala.collection.mutable.{HashSet, HashMap}

/**
 * Basic optimizers for the SLICK AST
 */
object Optimizer extends Logging {

  def apply(n: Node): Node = {
    if(logger.isDebugEnabled) {
      logger.debug("source:", n)
    }
    val n2 = localizeRefs(n)
    if(logger.isDebugEnabled) {
      AnonSymbol.assignNames(n2, prefix = "s", force = true)
      if(n2 ne n) logger.debug("localized refs:", n2)
    }
    val n3 = ReconstructProducts(n2)
    if(n3 ne n2) logger.debug("products reconstructed:", n3)
    val n4 = inlineRefs(n3)
    if(n4 ne n3) logger.debug("refs inlined:", n4)
    n4
  }

  /*def apply(n: Node): Node = {
    if(logger.isDebugEnabled) {
      //AnonSymbol.assignNames(n, force = true)
      logger.debug("source:", n)
    }
    val n2 = standard(n)
    if(n2 ne n) logger.debug("optimized:", n2)
    val n3 = Columnizer(n2)
    if(n3 ne n2) logger.debug("columnized:", n3)
    val n4 = assignUniqueSymbols(n3)
    if(logger.isDebugEnabled && (n4 ne n3)) {
      logger.debug("unique symbols:", n4)
    }
    val n5 = RewriteGenerators(n4)
    if(logger.isDebugEnabled && (n5 ne n4)) {
      AnonSymbol.assignNames(n5, "r")
      logger.debug("generators rewritten:", n5)
    }
    n5
  }*/

  /** Replace GlobalSymbols by AnonSymbols and collect them in a LetDynamic */
  def localizeRefs(tree: Node): Node = {
    val map = new HashMap[AnonSymbol, Node]
    val newNodes = new HashMap[AnonSymbol, Node]
    val tr = new Transformer {
      def replace = {
        case r: RefNode => r.nodeMapReferences {
          case GlobalSymbol(name, target) =>
            val s = new AnonSymbol
            map += s -> target
            newNodes += s -> target
            s
          case s => s
        }
      }
    }
    val tree2 = tr.once(tree)
    while(!newNodes.isEmpty) {
      val m = newNodes.toMap
      newNodes.clear()
      m.foreach { case (sym, n) => map += sym -> tr.once(n) }
    }
    if(map.isEmpty) tree2 else LetDynamic(map.toSeq, tree2)
  }

  /**
   * Inline references to global symbols which occur only once in a Ref node.
   * Paths are always inlined, no matter how many times they occur.
   * Symbols used in a FROM position are always inlined.
   *
   * Inlining behaviour can be controlled with optional parameters.
   *
   * We also remove identity Binds here to avoid an extra pass just for that.
   * TODO: Necessary? The conversion to relational trees should inline them anyway.
   */
  def inlineRefs(tree: Node, unique: Boolean = true, paths: Boolean = true, from: Boolean = true, all: Boolean = false): (Node) = {
    val counts = new HashMap[AnonSymbol, Int]
    tree.foreach {
      case r: RefNode => r.nodeReferences.foreach {
        case a: AnonSymbol =>
          counts += a -> (counts.getOrElse(a, 0) + 1)
        case s =>
      }
      case _ =>
    }
    val (tree2, globals) = tree match {
      case LetDynamic(defs, in) => (in, defs.toMap)
      case n => (n, Map[Symbol, Node]())
    }
    logger.debug("counts: "+counts)
    val globalCounts = counts.filterKeys(globals.contains)
    val toInlineAll = globalCounts.iterator.map(_._1).toSet
    logger.debug("symbols to inline in FROM positions: "+toInlineAll)
    val toInline = globalCounts.iterator.filter { case (a, i) =>
      all ||
      (unique && i == 1) ||
      (paths && Path.unapply(globals(a)).isDefined)
    }.map(_._1).toSet
    logger.debug("symbols to inline everywhere: "+toInline)
    val inlined = new HashSet[Symbol]
    def deref(a: AnonSymbol) = { inlined += a; globals(a) }
    lazy val tr: Transformer = new Transformer {
      def replace = {
        case f @ FilteredQuery(_, Ref(a: AnonSymbol)) if (all || from) && toInlineAll.contains(a) =>
          tr.once(f.nodeMapFrom(_ => deref(a)))
        case b @ Bind(_, Ref(a: AnonSymbol), _) if (all || from) && toInlineAll.contains(a) =>
          tr.once(b.copy(from = deref(a)))
        case j: JoinNode if(all || from) =>
          val l = j.left match {
            case Ref(a: AnonSymbol) if toInlineAll.contains(a) => deref(a)
            case x => x
          }
          val r = j.right match {
            case Ref(a: AnonSymbol) if toInlineAll.contains(a) => deref(a)
            case x => x
          }
          if((l eq j.left) && (r eq j.right)) j else tr.once(j.nodeCopyJoin(left = l, right = r))
        case Ref(a: AnonSymbol) if toInline.contains(a) =>
          tr.once(deref(a))
        // Remove identity Bind
        case Bind(gen, from, Pure(Ref(sym))) if gen == sym => tr.once(from)
      }
    }
    val tree3 = tr.once(tree2)
    val globalsLeft = globals.filterKeys(a => !inlined.contains(a))
    if(globalsLeft.isEmpty) tree3
    else LetDynamic(globalsLeft.iterator.map{ case (sym, n) => (sym, tr.once(n)) }.toSeq, tree3)
  }

  /**
   * Expand returned tables
   */











  /**
   * Ensure that all symbol definitions in a tree are unique
   */
  def assignUniqueSymbols(tree: Node): Node = {
    class Scope(val symbol: Symbol, parent: Option[Scope]) extends (Symbol => Symbol) {
      val replacement = new AnonSymbol
      private val local = new HashMap[Symbol, Scope]
      def in(s: Symbol) = local.getOrElseUpdate(s, new Scope(s, Some(this)))
      def find(s: Symbol): Option[Scope] =
        local.get(s).orElse(parent.flatMap(_.find(s)))
      def apply(s: Symbol) = find(s).map(_.replacement).getOrElse(s)
      def dumpString(prefix: String = "", indent: String = "", builder: StringBuilder = new StringBuilder): StringBuilder = {
        builder.append(indent + prefix + symbol + " -> " + replacement + "\n")
        local.foreach { case (_, scope) => scope.dumpString("", indent + "  ", builder) }
        builder
      }
    }
    def buildSymbolTable(n: Node, scope: Scope) {
      n match {
        case d: DefNode =>
          val defs = d.nodeGenerators.toMap
          defs.foreach{ case (sym, ch) => buildSymbolTable(ch, scope.in(sym)) }
          val other = d.nodePostGeneratorChildren.foreach(ch => buildSymbolTable(ch, scope))
        case n => n.nodeChildren.foreach(ch => buildSymbolTable(ch, scope))
      }
    }
    val rootSym = new AnonSymbol
    rootSym.name = "-root-"
    val rootScope = new Scope(rootSym, None)
    buildSymbolTable(tree, rootScope)
    def tr(n: Node, scope: Scope): Node = n match {
      case d: DefNode =>
        d.nodeMapScopedChildren{ case (symO, ch) =>
          val chScope = symO match {
            case None => scope
            case Some(sym) => scope.find(sym).getOrElse(scope)
          }
          tr(ch, chScope)
        }.asInstanceOf[DefNode].nodeMapGenerators(scope)
      case r @ Ref(s) =>
        val ns = scope(s)
        if(s == ns) r else Ref(ns)
      case r @ TableRef(s) =>
        val ns = scope(s)
        if(s == ns) r else TableRef(ns)
      case i @ InRef(s, what) => scope.find(s) match {
        case None => i
        case Some(refScope) =>
          InRef(refScope.replacement, tr(what, refScope))
      }
      case n => n.nodeMapChildren(ch => tr(ch, scope))
    }
    val res = tr(tree, rootScope)
    if(logger.isDebugEnabled && (res ne tree)) {
      AnonSymbol.assignNames(res, "u")
      logger.debug("rootScope:\n" + rootScope.dumpString(indent = "  "))
    }
    res
  }
}