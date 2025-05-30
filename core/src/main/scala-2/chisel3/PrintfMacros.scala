// SPDX-License-Identifier: Apache-2.0

package chisel3

import chisel3.internal._
import chisel3.internal.Builder.pushCommand
import chisel3.experimental.SourceInfo
import chisel3.{layer, layers}
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

object PrintfMacrosCompat {
  def _applyMacroWithInterpolatorCheck(
    c: blackbox.Context
  )(fmt: c.Tree, data: c.Tree*)(sourceInfo: c.Tree): c.Tree = {
    import c.universe._
    _checkFormatString(c)(fmt)
    // We just want to call the same function but on the packed Printable
    val obj = c.prefix.tree.symbol
    val func = c.macroApplication.symbol.name.toTermName
    q"$obj.$func(_root_.chisel3.Printable.pack($fmt, ..$data))($sourceInfo)"
  }

  private[chisel3] def _checkFormatString(c: blackbox.Context)(fmt: c.Tree): Unit = {
    import c.universe._

    val errorString = "The s-interpolator prints the Scala .toString of Data objects rather than the value " +
      "of the hardware wire during simulation. Use the cf-interpolator instead. If you want " +
      "an elaboration time print, use println."

    // Error on Data in the AST by matching on the Scala 2.13 string
    // interpolation lowering to concatenation
    def throwOnChiselData(x: c.Tree): Unit = x match {
      case q"$x+$y" => {
        if (x.tpe <:< typeOf[chisel3.Data] || y.tpe <:< typeOf[chisel3.Data]) {
          c.error(c.enclosingPosition, errorString)
        } else {
          throwOnChiselData(x)
          throwOnChiselData(y)
        }
      }
      case _ =>
    }
    throwOnChiselData(fmt)

    fmt match {
      case q"scala.StringContext.apply(..$_).s(..$_)" =>
        c.error(
          c.enclosingPosition,
          errorString
        )
      case _ =>
    }
  }
}
