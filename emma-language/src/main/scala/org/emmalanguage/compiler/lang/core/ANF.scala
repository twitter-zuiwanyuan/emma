/*
 * Copyright © 2014 TU Berlin (emma@dima.tu-berlin.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.emmalanguage
package compiler.lang.core

import compiler.Common
import compiler.lang.source.Source
import util.Monoids

import cats.std.all._
import shapeless._

import scala.annotation.tailrec

/** Administrative Normal Form (ANF) bypassing control-flow and for-comprehensions. */
private[core] trait ANF extends Common {
  this: Source with Core =>

  import UniverseImplicits._
  import Core.{Lang => core}
  import Source.{Lang => src}

  /** Administrative Normal Form (ANF) bypassing control-flow and for-comprehensions. */
  private[core] object ANF {

    /** The ANF transformation. */
    private lazy val anf: u.Tree => u.Tree = api.BottomUp
      // Inherit all method definitions in scope.
      .inherit { case core.Let(_, defs, _) =>
        (for (core.DefDef(method, _, _, _, _) <- defs) yield method).toSet
      }
      // Keep track if the current tree is a subtree of a type-tree.
      .withParent.inherit { case tree => is.tpe(tree) } (Monoids.disj)
      .withOwner.transformWith {
        // Bypass type-trees
        case Attr.inh(tree, _ :: true :: _) =>
          tree

        // Bypass atomics (except in lambdas, methods and comprehensions)
        case Attr.inh(src.Atomic(atom), _ :: _ :: Some(parent) :: _) => parent match {
          case src.Lambda(_, _, _) => src.Block()(atom)
          case core.DefDef(_, _, _, _, _) => src.Block()(atom)
          case Comprehension(_) => src.Block()(atom)
          case _ => atom
        }

        // Bypass parameters
        case Attr.none(param @ src.ParDef(_, _, _)) =>
          param

        // Bypass comprehensions
        case Attr.none(comprehension @ Comprehension(_)) =>
          src.Block()(comprehension)

        // Bypass local method calls in branches
        case Attr.inh(
          call @ src.DefCall(_, method, _, _*),
          _ :: _ :: Some(src.Branch(_, _, _)) :: local :: _
        ) if local(method) => call

        // Simplify RHS
        case Attr.none(src.VarMut(lhs, rhs)) =>
          val (stats, expr) = decompose(rhs, unline = true)
          val mut = src.VarMut(lhs, expr)
          if (stats.isEmpty) mut
          else src.Block(stats :+ mut: _*)()

        // Simplify RHS
        case Attr.none(src.BindingDef(lhs, rhs, flags)) =>
          val (stats, expr) = decompose(rhs, unline = true)
          val dfn = core.BindingDef(lhs, expr, flags)
          src.Block(stats :+ dfn: _*)()

        // Simplify expression
        case Attr.inh(src.TypeAscr(target, tpe), owner :: _) =>
          val (stats, expr) = decompose(target, unline = false)
          if (tpe =:= api.Type.of(expr)) {
            src.Block(stats: _*)(expr)
          } else {
            val nme = api.TermName.fresh(nameOf(expr))
            val lhs = api.ValSym(owner, nme, tpe)
            val rhs = core.TypeAscr(expr, tpe)
            val dfn = core.ValDef(lhs, rhs)
            val ref = core.ValRef(lhs)
            src.Block(stats :+ dfn: _*)(ref)
          }

        // Simplify target
        case Attr.inh(src.ModuleAcc(target, module) withType tpe, owner :: _) =>
          val (stats, expr) = decompose(target, unline = false)
          val nme = api.TermName.fresh(module)
          val lhs = api.ValSym(owner, nme, tpe)
          val rhs = core.ModuleAcc(expr, module)
          val dfn = core.ValDef(lhs, rhs)
          val ref = core.ValRef(lhs)
          src.Block(stats :+ dfn: _*)(ref)

        // Simplify target & arguments
        case Attr.inh(
          src.DefCall(target, method, targs, argss@_*) withType tpe,
          owner :: _ :: _ :: local :: _
        ) =>
          val (tgtStats, tgtExpr) = target
            .map(decompose(_, unline = false))
            .map { case (stats, expr) => (stats, Some(expr)) }
            .getOrElse(Seq.empty, None)

          val (argStats, argExprss) = decompose(argss, unline = false)
          val allStats = tgtStats ++ argStats
          val call = core.DefCall(tgtExpr)(method, targs: _*)(argExprss: _*)
          if (local contains method) {
            src.Block(allStats: _*)(call)
          } else {
            val nme = api.TermName.fresh(method)
            val lhs = api.ValSym(owner, nme, tpe)
            val dfn = core.ValDef(lhs, call)
            val ref = core.ValRef(lhs)
            src.Block(allStats :+ dfn: _*)(ref)
          }

        // Simplify arguments
        case Attr.inh(src.Inst(clazz, targs, argss@_*) withType tpe, owner :: _) =>
          val (stats, exprss) = decompose(argss, unline = false)
          val nme = api.TermName.fresh(api.Sym.of(clazz))
          val lhs = api.ValSym(owner, nme, tpe)
          val rhs = core.Inst(clazz, targs: _*)(exprss: _*)
          val dfn = core.ValDef(lhs, rhs)
          val ref = core.ValRef(lhs)
          src.Block(stats :+ dfn: _*)(ref)

        // Flatten blocks
        case Attr.inh(src.Block(outer, expr), owner :: _) =>
          val (inner, result) = decompose(expr, unline = false)
          val flat = outer.flatMap {
            case src.Block(stats, src.Atomic(_)) => stats
            case src.Block(stats, stat) => stats :+ stat
            case stat => Some(stat)
          }

          src.Block(flat ++ inner: _*)(result)

        // All lambdas on the RHS
        case Attr.none(lambda @ src.Lambda(fun, _, _) withType tpe) =>
          val nme = api.TermName.fresh(api.TermName.lambda)
          val lhs = api.ValSym(fun.owner, nme, tpe)
          val dfn = core.ValDef(lhs, lambda)
          val ref = core.ValRef(lhs)
          src.Block(dfn)(ref)

        // All branches on the RHS
        case Attr.inh(
          src.Branch(cond, thn, els) withType tpe,
          owner :: _ :: _ :: local :: _
        ) =>
          val (stats, expr) = decompose(cond, unline = false)
          val branch = core.Branch(expr, thn, els)
          if (isDSCF(branch)(local)) {
            src.Block(stats: _*)(branch)
          } else {
            val nme = api.TermName.fresh("if")
            val lhs = api.ValSym(owner, nme, tpe)
            val dfn = core.ValDef(lhs, branch)
            val ref = core.ValRef(lhs)
            src.Block(stats :+ dfn: _*)(ref)
          }
      }.andThen(_.tree)

    /**
     * Converts a tree into administrative normal form (ANF).
     *
     * == Preconditions ==
     *
     * - There are no name clashes (can be ensured with `resolveNameClashes`).
     *
     * == Postconditions ==
     *
     * - Introduces dedicated symbols for chains of length greater than one.
     * - Ensures that all function arguments are trivial identifiers.
     *
     * @return An ANF version of the input tree.
     */
    lazy val transform: u.Tree => u.Tree = (tree: u.Tree) => {
      lazy val clashes = nameClashes(tree)
      assert(clashes.isEmpty, s"Tree has name clashes:\n${clashes.mkString(", ")}")
      anf(tree)
    }

    /**
     * Inlines `Ident` return expressions in blocks whenever the referred symbol is used only once.
     * The resulting tree is said to be in ''simplified ANF'' form.
     *
     * == Preconditions ==
     * - The input `tree` is in ANF (see [[transform]]).
     *
     * == Postconditions ==
     * - `Ident` return expressions in blocks have been inlined whenever possible.
     */
    lazy val inlineLetExprs: u.Tree => u.Tree =
      api.BottomUp.withValDefs.withValUses.transformWith {
        case Attr.syn(src.Block(stats, src.ValRef(target)), uses :: defs :: _)
          if defs.contains(target) && uses(target) == 1 =>
            val value = defs(target)
            src.Block(stats.filter(_ != value): _*)(value.rhs)
      }.andThen(_.tree)

    /**
     * Introduces `Ident` return expressions in blocks whenever the original expr is not a ref or
     * literal.The opposite of [[inlineLetExprs]].
     *
     * == Preconditions ==
     * - The input `tree` is in ANF (see [[transform]]).
     *
     * == Postconditions ==
     * - `Ident` return expressions in blocks have been introduced whenever possible.
     */
    lazy val uninlineLetExprs: u.Tree => u.Tree = api.TopDown
      // Accumulate all method definitions seen so far.
      .accumulate { case core.Let(_, defs, _) =>
        (for (core.DefDef(method, _, _, _, _) <- defs) yield method).toSet
      }.withOwner.transformWith {
        case Attr.acc(let @ core.Let(_, _, expr), local :: _)
          if isDSCF(expr)(local) => let

        case Attr.inh(core.Let(vals, defs, expr), owner :: _) =>
          val nme = api.TermName.fresh("x")
          val lhs = api.ValSym(owner, nme, expr.tpe)
          val ref = core.Ref(lhs)
          val dfn = core.ValDef(lhs, expr)
          core.Let(vals :+ dfn: _*)(defs: _*)(ref)
      }.andThen(_.tree)

    /**
     * Un-nests nested blocks.
     *
     * == Preconditions ==
     * - Except the nested blocks, the input tree is in ANF form.
     *
     * == Postconditions ==
     * - An ANF tree where all nested blocks have been flattened.
     */
    lazy val flatten: u.Tree => u.Tree =
      api.BottomUp.transform {
        case parent @ core.Let(vals, defs, expr) if hasNestedLets(parent) =>
          // Flatten nested let expressions in value position without control flow.
          val flatVals = vals.flatMap {
            case core.ValDef(lhs, core.Let(nestedVals, Seq(), nestedExpr), flags) =>
              (nestedVals, nestedExpr) match {
                // Match: { ..vals; val x = expr; x }
                case (prefix :+ core.ValDef(x, rhs, _), core.ValRef(y))
                  if x == y => prefix :+ core.ValDef(lhs, rhs, flags)
                // Match: { ..vals; expr }
                case (prefix, rhs) =>
                  prefix :+ core.ValDef(lhs, rhs, flags)
              }
            case value =>
              Some(value)
          }

          // Flatten nested let expressions in expr position without control flow.
          val (exprVals, flatExpr) = expr match {
            case core.Let(nestedVals, Seq(), nestedExpr) =>
              (nestedVals, nestedExpr)
            case _ =>
              (Seq.empty, expr)
          }

          val (trimmedVals, trimmedExpr) = trimVals(flatVals ++ exprVals, flatExpr)
          core.Let(trimmedVals: _*)(defs: _*)(trimmedExpr)
      }.andThen(_.tree)

    // ---------------
    // Helper methods
    // ---------------

    // Handle degenerate cases where the suffix is of the form:
    // { ..vals; val x2 = x3; val x1 = x2; x1 }
    private def trimVals(vals: Seq[u.ValDef], expr: u.Tree): (Seq[u.ValDef], u.Tree) =
      (Seq.empty, vals.foldRight(expr, 0) {
        case (core.ValDef(x, rhs @ core.Atomic(_), _), (core.ValRef(y), n))
          if x == y => (rhs, n + 1)
        case (_, (rhs, n)) =>
          return (vals.dropRight(n), rhs)
      }._1)

    /** Does the input `let` block contain nested `let` expressions? */
    private def hasNestedLets(let: u.Block): Boolean = {
      def inStats = let.stats.exists {
        case core.ValDef(_, core.Let(_, Seq(), _), _) => true
        case _ => false
      }
      def inExpr = let.expr match {
        case core.Let(_, Seq(), _) => true
        case _ => false
      }
      inStats || inExpr
    }

    /** Returns the encoded name associated with this subtree. */
    @tailrec private def nameOf(tree: u.Tree): u.Name = tree match {
      case id: u.Ident => id.name.encodedName
      case value: u.ValDef => value.name.encodedName
      case method: u.DefDef => method.name.encodedName
      case u.Select(_, member) => member.encodedName
      case u.Typed(expr, _) => nameOf(expr)
      case u.Block(_, expr) => nameOf(expr)
      case u.Apply(target, _) => nameOf(target)
      case u.TypeApply(target, _) => nameOf(target)
      case _: u.Function => api.TermName.lambda
      case _ => api.TermName("x")
    }

    /** Decomposes a [[src.Block]] into statements and expressions. */
    private def decompose(tree: u.Tree, unline: Boolean)
      : (Seq[u.Tree], u.Tree) = tree match {
        case src.Block(stats :+ src.ValDef(x, rhs, _), src.ValRef(y))
          if unline && x == y => (stats, rhs)
        case src.Block(stats, expr) =>
          (stats, expr)
        case _ =>
          (Seq.empty, tree)
      }

    /** Decomposes a nested sequence of [[src.Block]]s into statements and expressions. */
    private def decompose(treess: Seq[Seq[u.Tree]], unline: Boolean)
      : (Seq[u.Tree], Seq[Seq[u.Tree]]) = {

      val stats = for {
        trees <- treess
        tree <- trees
        stat <- decompose(tree, unline)._1
      } yield stat

      val exprss = for (trees <- treess)
        yield for (tree <- trees)
          yield decompose(tree, unline)._2

      (stats, exprss)
    }

    /** Checks whether the given expression represents direct-style control-flow. */
    private def isDSCF(expr: u.Tree)(isLocal: u.MethodSymbol => Boolean): Boolean = expr match {
      //@formatter:off
      // 1a) branch with one local method call and one atomic
      case core.Branch(core.Atomic(_),
        core.DefCall(_, thn, _, _),
        core.Atomic(_)
      ) => isLocal(thn)
      // 1b) reversed version of 1a
      case core.Branch(core.Atomic(_),
        core.Atomic(_),
        core.DefCall(_, els, _, _)
      ) => isLocal(els)
      // 2) branch with two local method calls
      case core.Branch(core.Atomic(_),
        core.DefCall(_, thn, _, _),
        core.DefCall(_, els, _, _)
      ) => isLocal(thn) && isLocal(els)
      // 3) simple local method call
      case core.DefCall(_, method, _, _) => isLocal(method)
      // 4) simple atomic
      case core.Atomic(_) => true
      // 5) anything else
      case _ => false
      //@formatter:on
    }

    /** Extractor for arbitrary comprehensions. */
    private object Comprehension {
      def unapply(tree: u.Tree): Option[u.Tree] = tree match {
        case src.DefCall(Some(_), method, _, _*)
          if ComprehensionSyntax.ops(method) => Some(tree)
        case _ => None
      }
    }
  }
}