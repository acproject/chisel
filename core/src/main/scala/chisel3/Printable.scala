// SPDX-License-Identifier: Apache-2.0

package chisel3

import chisel3.experimental.SourceInfo
import chisel3.internal.Builder
import chisel3.internal.firrtl.ir.Component
import chisel3.FirrtlFormat.FormatWidth

import scala.collection.mutable
import scala.annotation.nowarn

import java.util.{MissingFormatArgumentException, UnknownFormatConversionException}

/** Superclass of things that can be printed in the resulting circuit
  *
  * Usually created using the custom string interpolator `p"..."`. Printable string interpolation is
  * similar to [[https://docs.scala-lang.org/overviews/core/string-interpolation.html String
  * interpolation in Scala]] For example:
  * {{{
  *   printf(p"The value of wire = \$wire\n")
  * }}}
  * This is equivalent to writing:
  * {{{
  *   printf("The value of wire = %d\n", wire)
  * }}}
  * All Chisel data types have a method `.toPrintable` that gives a default pretty print that can be
  * accessed via `p"..."`. This works even for aggregate types, for example:
  * {{{
  *   val myVec = VecInit(5.U, 10.U, 13.U)
  *   printf(p"myVec = \$myVec\n")
  *   // myVec = Vec(5, 10, 13)
  *
  *   val myBundle = Wire(new Bundle {
  *     val foo = UInt()
  *     val bar = UInt()
  *   })
  *   myBundle.foo := 3.U
  *   myBundle.bar := 11.U
  *   printf(p"myBundle = \$myBundle\n")
  *   // myBundle = Bundle(a -> 3, b -> 11)
  * }}}
  * Users can override the default behavior of `.toPrintable` in custom [[Bundle]] and [[Record]]
  * types.
  */
// TODO Add support for names of Modules
//   Currently impossible because unpack is called before the name is selected
//   Could be implemented by adding a new format specifier to Firrtl (eg. %m)
// TODO Should we provide more functions like map and mkPrintable?
sealed abstract class Printable {

  /** Unpack into format String and a List of String arguments (identifiers)
    * @note This must be called after elaboration when Chisel nodes actually
    *   have names
    */
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  def unpack(ctx: Component)(implicit info: SourceInfo): (String, Iterable[String])

  /** Unpack into a Seq of captured Bits arguments
    */
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  def unpackArgs: Seq[Bits]

  /** Unpack into format String and a List of Data arguments */
  def unpack: (String, Seq[Data])

  /** Allow for appending Printables like Strings */
  def +(that: Printable): Printables = Printables(List(this, that))

  /** Allow for appending Strings to Printables */
  final def +(that: String): Printables = this + PString(that)
}

object Printable {

  private[chisel3] def isNoArgSpecifier(c: Char): Boolean = c == '%' || c == 'm' || c == 'T'

  /** Pack standard printf fmt, args* style into Printable
    */
  def pack(fmt: String, data: Data*): Printable = {
    val args = data.iterator
    // Error handling
    def carrotAt(index: Int) = (" " * index) + "^"
    def errorMsg(index: Int) = {
      // Escape newlines because they mess up the error message
      val fmtEsc = fmt.replaceAll("\n", "\\\\n")
      s"""|    fmt = "$fmtEsc"
          |           ${carrotAt(index)}
          |    data = ${data.mkString(", ")}""".stripMargin
    }

    def checkArg(i: Int): Unit = {
      if (!args.hasNext) {
        val msg = "has no matching argument!\n" + errorMsg(i)
        // Exception wraps msg in s"Format Specifier '$msg'"
        throw new MissingFormatArgumentException(msg)
      }
      val _ = args.next()
    }
    var iter = 0
    var curr_start = 0
    val buf = mutable.ListBuffer.empty[String]
    while (iter < fmt.size) {
      // Encountered % which is either
      // 1. Describing a format specifier.
      // 2. %%, %m, or %T
      // 3. Dangling percent - most likely due to a typo - intended literal percent or forgot the specifier.
      // Try to give meaningful error reports
      if (fmt(iter) == '%') {
        if (iter != fmt.size - 1 && (!isNoArgSpecifier(fmt(iter + 1)) && !fmt(iter + 1).isWhitespace)) {
          checkArg(iter)
          buf += fmt.substring(curr_start, iter)
          curr_start = iter
          iter += 1
        }

        // Last character is %.
        else if (iter == fmt.size - 1) {
          val msg = s"Trailing %\n" + errorMsg(fmt.size - 1)
          throw new UnknownFormatConversionException(msg)
        }

        // A lone %
        else if (fmt(iter + 1).isWhitespace) {
          val msg = s"Unescaped % - add % if literal or add proper specifier if not\n" + errorMsg(iter + 1)
          throw new UnknownFormatConversionException(msg)
        }

        // %% or %m - hence increment by 2.
        else {
          iter += 2
        }
      }

      // Normal progression
      else {
        iter += 1
      }
    }
    require(
      !args.hasNext,
      s"Too many arguments! More format specifier(s) expected!\n" +
        errorMsg(fmt.size)
    )
    buf += fmt.substring(curr_start, iter)

    // The string received as an input to pack is already
    // treated  i.e. escape sequences are processed.
    // Since StringContext API assumes the parts are un-treated
    // treatEscapes is called within the implemented custom interpolators.
    // The literal \ needs to be escaped before sending to the custom cf interpolator.

    val bufEscapeBackSlash = buf.map(_.replace("\\", "\\\\"))
    StringContext(bufEscapeBackSlash.toSeq: _*).cf(data: _*)
  }

  /** Extensions for Printable */
  implicit class Extensions(pable: Printable) {

    /** Build a new Printable by applying a function to all sub-Printables
     *
     * @note This is an extension method instead of normal method to avoid conflicts with the implicit conversion from String to Printable
     */
    def map(f: Printable => Printable): Printable = pable match {
      case Printables(pables) =>
        Printables(pables.map(_.map(f))) // Note the nested map, Printables can contain Printables
      case _ => f(pable)
    }
  }

  private[chisel3] def checkScope(message: Printable, msg: String = "")(implicit info: SourceInfo): Unit = {
    def getData(x: Printable): Seq[Data] = {
      x match {
        case y: FirrtlFormat => Seq(y.bits)
        case Name(d)       => Seq(d)
        case FullName(d)   => Seq(d)
        case Printables(p) => p.flatMap(getData(_)).toSeq
        case _             => Seq() // Handles subtypes PString and Percent
      }
    }
    for (data <- getData(message)) {
      for (err <- data.checkVisible) {
        Builder.error(msg + err)
      }
    }
  }

  /** Extract the Data that will be arguments to Firrtl
    *
    * This ignores Printables that are resolved at Chisel-time (e.g. [[Name]] and [[FullName]])
    */
  private[chisel3] def unpackFirrtlArgs(pable: Printable): Seq[Data] =
    pable.map {
      case _: Name | _: FullName => PString("")
      case x                     => x
    }.unpack._2

  /** Resolve Printables that are resolved at Chisel-time */
  private[chisel3] def resolve(pable: Printable, ctx: Component)(implicit info: SourceInfo): Printable =
    pable.map {
      case Name(data)     => PString(data.ref.name)
      case FullName(data) => PString(data.ref.fullName(ctx))
      case s: SpecialFirrtlSubstitution => PString(s.substitutionString)
      case other => other
    }
}

@nowarn("msg=Use unpack with no arguments instead")
case class Printables(pables: Iterable[Printable]) extends Printable {
  require(pables.hasDefiniteSize, "Infinite-sized iterables are not supported!")

  // Optimize to reduce object allocations
  final override def +(that: Printable): Printables = that match {
    case that: Printables => Printables(pables ++ that.pables)
    case other => Printables(pables.toVector :+ other)
  }

  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpack(ctx: Component)(implicit info: SourceInfo): (String, Iterable[String]) = {
    val (fmts, args) = pables.map(_.unpack(ctx)).unzip
    (fmts.mkString, args.flatten)
  }

  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpackArgs: Seq[Bits] = pables.view.flatMap(_.unpackArgs).toList

  final def unpack: (String, Seq[Data]) = {
    val (fmts, args) = pables.toSeq.map(_.unpack).unzip
    (fmts.mkString, args.flatten)
  }
}

/** Wrapper for printing Scala Strings */
case class PString(str: String) extends Printable {
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpack(ctx: Component)(implicit info: SourceInfo): (String, Iterable[String]) =
    (str.replaceAll("%", "%%"), List.empty)

  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpackArgs: Seq[Bits] = List.empty

  final def unpack: (String, Seq[Data]) = (str.replaceAll("%", "%%"), List.empty)
}

/** Superclass for Firrtl format specifiers for Bits */
sealed abstract class FirrtlFormat(private[chisel3] val specifier: Char) extends Printable {
  def bits: Bits
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  def unpack(ctx: Component)(implicit info: SourceInfo): (String, Iterable[String]) = {
    val (str, Seq(bits)) = unpack
    (str, List(bits.ref.fullName(ctx)))
  }

  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  def unpackArgs: Seq[Bits] = List(bits)
}
object FirrtlFormat {
  final val legalSpecifiers = List('d', 'x', 'b', 'c')

  def unapply(x: Char): Option[Char] =
    Option(x).filter(x => legalSpecifiers contains x)

  /** Width modifiers for format specifiers */
  sealed trait FormatWidth {
    def toFormatString: String
  }
  object FormatWidth {

    /** Display using the minimum number of characters */
    case object Minimum extends FormatWidth {
      def toFormatString: String = "0"
    }

    /** Pad the display to the number of characters needed to display the maximum possible value for the signal width and formatting */
    case object Automatic extends FormatWidth {
      def toFormatString: String = ""
    }

    /** Display using the specified number of characters */
    case class Fixed(value: Int) extends FormatWidth {
      require(value != 0, s"For minimum width, use FormatWidth.Minimum, not FormatWidth.Fixed(0)")
      require(value > 0, s"Width.Fixed must be positive, got $value")
      def toFormatString: String = value.toString
    }
  }

  /** Helper for constructing Firrtl Formats
    * Accepts data to simplify pack
    */
  @deprecated("Use FirrtlFormat.parse instead", "Chisel 7.0.0")
  def apply(specifier: String, data: Data): FirrtlFormat = {
    val bits = data match {
      case b: Bits => b
      case d => throw new Exception(s"Trying to construct FirrtlFormat with non-bits $d!")
    }
    specifier match {
      case "d" => Decimal(bits)
      case "x" => Hexadecimal(bits)
      case "b" => Binary(bits)
      case "c" => Character(bits)
      case c   => throw new Exception(s"Illegal format specifier '$c'!")
    }
  }

  /** Helper for parsing Firrtl Formats
    *
    * @param specifier the format specifier, e.g. `%0d`
    */
  def parse(specifier: String, bits: Bits): Either[String, FirrtlFormat] = {
    if (!specifier.startsWith("%")) return Left(s"Format specifier '$specifier' must start with '%'!")
    specifier.last match {
      case 'c' =>
        if (specifier.length != 2) Left(s"'%c' does not support width modifiers!")
        else Right(Character(bits))
      case 'd' | 'x' | 'b' =>
        val modifier = specifier.slice(1, specifier.length - 1)
        if (modifier.headOption.contains('-'))
          return Left("Chisel does not support non-standard Verilog left-justified format specifiers!")
        if (modifier.headOption.contains('0') && modifier.length > 1)
          return Left("Chisel does not support non-standard Verilog zero-padded format specifiers!")
        if (!modifier.forall(_.isDigit)) return Left("Width modifier must be a positive integer!")
        val width = modifier match {
          case ""    => FormatWidth.Automatic
          case "0"   => FormatWidth.Minimum
          case value => FormatWidth.Fixed(value.toInt)
        }
        specifier.last match {
          case 'd' => Right(Decimal(bits, width))
          case 'x' => Right(Hexadecimal(bits, width))
          case 'b' => Right(Binary(bits, width))
        }
      case bad => Left(s"Illegal format specifier '$bad'!")
    }
  }
}

/** Format bits as Decimal */
case class Decimal(bits: Bits, width: FormatWidth = FormatWidth.Automatic) extends FirrtlFormat('d') {
  def unpack: (String, Seq[Data]) = ("%" + width.toFormatString + "d", List(bits))
}

/** Format bits as Hexidecimal */
case class Hexadecimal(bits: Bits, width: FormatWidth = FormatWidth.Automatic) extends FirrtlFormat('x') {
  def unpack: (String, Seq[Data]) = ("%" + width.toFormatString + "x", List(bits))
}

/** Format bits as Binary */
case class Binary(bits: Bits, width: FormatWidth = FormatWidth.Automatic) extends FirrtlFormat('b') {
  def unpack: (String, Seq[Data]) = ("%" + width.toFormatString + "b", List(bits))
}

/** Format bits as Character */
case class Character(bits: Bits) extends FirrtlFormat('c') {
  def unpack: (String, Seq[Data]) = ("%c", List(bits))
}

/** Put innermost name (eg. field of bundle) */
case class Name(data: Data) extends Printable {
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpack(ctx: Component)(implicit info: SourceInfo): (String, Iterable[String]) = (data.ref.name, List.empty)
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpackArgs: Seq[Bits] = List.empty
  final def unpack:     (String, Seq[Data]) = ("%n", List(data))
}

/** Put full name within parent namespace (eg. bundleName.field) */
case class FullName(data: Data) extends Printable {
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpack(ctx: Component)(implicit info: SourceInfo): (String, Iterable[String]) =
    (data.ref.fullName(ctx), List.empty)
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpackArgs: Seq[Bits] = List.empty
  final def unpack:     (String, Seq[Data]) = ("%N", List(data))
}

/** Represents escaped percents */
case object Percent extends Printable {
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpack(ctx: Component)(implicit info: SourceInfo): (String, Iterable[String]) = ("%%", List.empty)
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpackArgs: Seq[Bits] = List.empty
  final def unpack:     (String, Seq[Data]) = ("%%", List.empty)
}

/** Printable with special representation in FIRRTL
  *
  * @note The name of the singleton object exactly matches the FIRRTL value.
  */
sealed trait SpecialFirrtlSubstitution { self: Singleton with Printable =>
  private[chisel3] final def substitutionString: String = "{{" + this.getClass.getSimpleName.dropRight(1) + "}}"
}

/** Represents the hierarchical name in the Verilog (`%m`) */
case object HierarchicalModuleName extends Printable with SpecialFirrtlSubstitution {
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpack(ctx: Component)(implicit info: SourceInfo): (String, Iterable[String]) = ("%m", List.empty)
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpackArgs: Seq[Bits] = List.empty
  final def unpack:     (String, Seq[Data]) = ("%m", List.empty)
}

/** Represents the simulation time in the Verilog, similar to `%t` + `$time` */
case object SimulationTime extends Printable with SpecialFirrtlSubstitution {
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpack(ctx: Component)(implicit info: SourceInfo): (String, Iterable[String]) = ("%T", List.empty)
  @deprecated("Use unpack with no arguments instead.", "Chisel 7.0.0")
  final def unpackArgs: Seq[Bits] = List.empty
  final def unpack:     (String, Seq[Data]) = ("%T", List.empty)
}
