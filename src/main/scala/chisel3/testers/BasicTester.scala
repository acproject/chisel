// SPDX-License-Identifier: Apache-2.0

package chisel3.testers
import chisel3._

import chisel3.internal.Builder.pushCommand
import chisel3.internal.firrtl._
import chisel3.experimental.SourceInfo

@deprecated("Use a `Module` instead of `BasicTester`", "Chisel 6.7.0")
class BasicTester extends Module() {
  // The testbench has no IOs, rather it should communicate using printf, assert, and stop.
  val io = IO(new Bundle() {})

  def popCount(n: Long): Int = n.toBinaryString.count(_ == '1')

  /** Ends the test reporting success.
    *
    * Does not fire when in reset (defined as the encapsulating Module's
    * reset). If your definition of reset is not the encapsulating Module's
    * reset, you will need to gate this externally.
    */
  def stop()(implicit sourceInfo: SourceInfo): Unit = chisel3.stop()

  /** The finish method provides a hook that subclasses of BasicTester can use to
    * alter a circuit after their constructor has been called.
    * For example, a specialized tester subclassing BasicTester could override finish in order to
    * add flow control logic for a decoupled io port of a device under test
    */
  def finish(): Unit = {}

  atModuleBodyEnd(finish())
}
