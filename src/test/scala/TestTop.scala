package hbl2demo

import chisel3._
import circt.stage.{ChiselStage, FirtoolOption}
import chisel3.util._
import org.chipsalliance.cde.config._
import chisel3.stage.ChiselGeneratorAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import huancun._
import coupledL2.prefetch._
import coupledL2.tl2tl._
import utility._

import coupledL2.{EnableCHI, L2ParamKey, MatrixDataBundle, MatrixKey}
import coupledL2._

import scala.collection.mutable.ArrayBuffer

import hbl2demo.RegInfo

import chiseltest._
import chiseltest.{VerilatorBackendAnnotation, WriteVcdAnnotation}
import chiseltest.simulator.{VerilatorCFlags, VerilatorFlags}
import org.scalatest.flatspec._
import org.scalatest.matchers.should._

case object L2BanksKey extends Field[Int]
case object L3BanksKey extends Field[Int]
case object MNumKey extends Field[Int]

object baseConfig {
  def apply(maxHartIdBits: Int) = {
    new Config((_, _, _) => {
      case MaxHartIdBits => maxHartIdBits
      case L2ParamKey =>
        L2Param(
          ways = 8,
          sets = 512,
          channelBytes = TLChannelBeatBytes(64),
          blockBytes = 128
        )
    })
  }
}

private[hbl2demo] object TestTopAMEFirtoolOptions {
  def apply() = Seq(
    FirtoolOption("--disable-annotation-unknown"),
    FirtoolOption("--repl-seq-mem"),
    FirtoolOption("--repl-seq-mem-file=TestTop.sv.conf"),
    FirtoolOption("--lowering-options=explicitBitcast")
  )
}

object baseConfigAME {
  def apply(maxHartIdBits: Int, l2_banks: Int, l3_banks: Int, m_num: Int) = {
    new Config((_, _, _) => {
      case MaxHartIdBits => maxHartIdBits
      case L2ParamKey =>
        L2Param(
          ways = 8,
          sets = 512,
          channelBytes = TLChannelBeatBytes(64),
          enablePerf = false
          // blockBytes = 128
        )
      case L2BanksKey => l2_banks
      case L3BanksKey => l3_banks
      case MNumKey    => m_num
    })
  }
}

/*
// CUATION: no explcit IO in lazy module

class TestTopIO(implicit p: Parameters) extends Bundle {
  val init_fire = Input(Bool())
  val ld_fire   = Input(Bool())
  val st_fire   = Input(Bool())
  val init_done = Output(Bool())
  val ld_done   = Output(Bool())
  val st_done   = Output(Bool())

  val reg_in  = Input(new RegInfo)
  val reg_out = Output(new RegInfo)

}
 */

class TestTop_AMU_L2_L3_RAM()(implicit p: Parameters, params: TLBundleParameters) extends LazyModule {

  override lazy val desiredName: String = "SimTop"
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)

  // val io = IO(new TestTopIO)

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(
      Seq(
        TLMasterPortParameters.v2(
          masters = Seq(
            TLMasterParameters.v1(
              name = name,
              sourceId = IdRange(0, sources),
              supportsProbe = TransferSizes(cacheParams.blockBytes)
            )
          ),
          channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
          minLatency = 1,
          echoFields = Nil,
          requestFields = Seq(AliasField(2), PrefetchField()),
          responseKeys = cacheParams.respKey
        )
      )
    )
    masterNode
  }
  val l2_banks = p(L2BanksKey)
  val l3_banks = p(L3BanksKey)
  val m_num = p(MNumKey)
  val l1d = createClientNode(s"l1d", 32)
  val l1i = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        clients = Seq(
          TLMasterParameters.v1(
            name = s"l1i",
            sourceId = IdRange(0, 32)
          )
        )
      )
    )
  )

  // val matrix_nodes = (0 until 1).flatMap { i =>
  //   (0 until m_num).map { j =>
  //     TLClientNode(Seq(
  //       TLMasterPortParameters.v1(
  //         clients = Seq(TLMasterParameters.v1(
  //           name = s"matrix${i}_${j}",
  //           sourceId = IdRange(0, 32),
  //           )),
  //           requestFields = Seq(MatrixField(2))
  //       )
  //     ))
  //   }
  // }
  val amu = LazyModule(new AMU()(p, params))
  val matrix_nodes = amu.matrix_nodes
  val c_nodes = Seq(l1d)
  val l1i_nodes = Seq(l1i)
  val ul_nodes = l1i_nodes ++ matrix_nodes
  val l2 = LazyModule(new TL2TLCoupledL2()(p.alter((site, here, up) => {
    case L2ParamKey =>
      L2Param(
        name = s"l2",
        ways = 8,
        sets = 512,
        channelBytes = TLChannelBeatBytes(32),
        blockBytes = 64,
        clientCaches = Seq(L1Param(aliasBitsOpt = Some(2), vaddrBitsOpt = Some(16))),
        echoField = Seq(DirtyField())
      )
    case huancun.BankBitsKey => log2Ceil(8)
    // case BankBitsKey => log2Ceil(l2_banks)
    case LogUtilsOptionsKey =>
      LogUtilsOptions(
        false,
        here(L2ParamKey).enablePerf,
        // false,
        here(L2ParamKey).FPGAPlatform
      )
    case PerfCounterOptionsKey =>
      PerfCounterOptions(
        here(L2ParamKey).enablePerf && !here(L2ParamKey).FPGAPlatform,
        here(L2ParamKey).enableRollingDB && !here(L2ParamKey).FPGAPlatform,
        XSPerfLevel.withName("VERBOSE"),
        0
        // false,
      )
  })))

  val l3 = LazyModule(new HuanCun()(p.alter((site, here, up) => {
    case HCCacheParamsKey =>
      HCCacheParameters(
        name = "l3",
        level = 3,
        ways = 16,
        sets = 4096,
        inclusive = false,
        clientCaches = Seq(
          CacheParameters(
            name = s"l2",
            sets = 4096,
            ways = 16,
            blockGranularity = log2Ceil(128)
          )
        ),
        echoField = Seq(DirtyField()),
        simulation = true
      )
    case LogUtilsOptionsKey =>
      LogUtilsOptions(
        here(HCCacheParamsKey).enableDebug,
        here(HCCacheParamsKey).enablePerf,
        // false,
        here(HCCacheParamsKey).FPGAPlatform
      )
    case PerfCounterOptionsKey =>
      PerfCounterOptions(
        here(HCCacheParamsKey).enablePerf && !here(HCCacheParamsKey).FPGAPlatform,
        // false,
        false,
        XSPerfLevel.withName("VERBOSE"),
        0
      )
  })))

  val l1xbar = TLXbar()
  val l2xbar = TLXbar()
  val l3xbar = TLXbar()
  val l2bankBinders = BankBinder(l2_banks, 64)
  val l3bankBinders = BankBinder(l3_banks, 64)
  val ram = LazyModule(new TLRAM(AddressSet(0, 0xff_ffffL), beatBytes = 32))

  c_nodes.zipWithIndex.map {
    case (c, i) =>
      l1xbar := TLBuffer() := TLLogger(s"L2_L1D[${i}]", true) := c
  }

  l1i_nodes.zipWithIndex.map {
    case (ul, i) =>
      l1xbar := TLBuffer() := TLLogger(s"L2_L1I[${i}]", true) := ul
  }

  matrix_nodes.zipWithIndex.map {
    case (m, i) =>
      l1xbar := TLBuffer() := TLLogger(s"L2_Matrix[${i}]", true) := m
  }
  // amu.matrix_nodes.zipWithIndex map{ case(ul,i) =>
  //       l1xbar := TLBuffer() := TLLogger(s"L2_Matrix[${i}]", true) := ul
  // }

  l2bankBinders :*= l2.node :*= TLBuffer() :*= l1xbar
  l3xbar :*= TLBuffer() :*= l2xbar :=* l2bankBinders
  ram.node :=
    TLXbar() :=
    TLFragmenter(32, 64) :=
    TLCacheCork() :=
    TLClientsMerger() :=
    TLDelayer(delayFactor) :=
    TLLogger(s"MEM_L3", true) :=
    TLXbar() :=*
      l3bankBinders :*=
    l3.node :*= l3xbar

  lazy val module = new LazyModuleImp(this) {
    val timer = WireDefault(0.U(64.W))
    val logEnable = WireDefault(false.B)
    val clean = WireDefault(false.B)
    val dump = WireDefault(false.B)

    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)

    c_nodes.zipWithIndex.foreach {
      case (node, i) =>
        node.makeIOs()(ValName(s"master_port_$i"))
    }
    l1i_nodes.zipWithIndex.foreach {
      case (node, i) =>
        node.makeIOs()(ValName(s"master_ul_port_0_${i}"))
    }
    l2.module.io.hartId := DontCare
    l2.module.io.pfCtrlFromCore := DontCare
    l2.module.io.debugTopDown <> DontCare
    l2.module.io.l2_tlb_req <> DontCare
    l2.module.io.matrixDataOut512L2 <> DontCare

    // For matrix get , l2 return data
    val matrix_data_out = amu.module.io.matrix_data_in
    //IO(Vec(l2_banks, DecoupledIO(new MatrixDataBundle())))
    matrix_data_out <> l2.module.io.matrixDataOut512L2

    // initialize
    amu.module.io.init_fire := false.B
    amu.module.io.ld_fire := true.B
    amu.module.io.st_fire := false.B
    amu.module.io.reg_in := DontCare

    /*
    // connect amu & testtop
    amu.module.io.init_fire := io.init_fire
    amu.module.io.ld_fire   := io.ld_fire
    amu.module.io.st_fire   := io.st_fire

    io.init_done := amu.module.io.init_done
    io.ld_done := amu.module.io.ld_done
    io.st_done := amu.module.io.st_done

    amu.module.io.reg_in := io.reg_in
    io.reg_out := amu.module.io.reg_out
     */
  }

}

/*  L1D L1I
 *  \  /
 *   L2 -- (Matrix sends Get/Put)
 *   |
 *  L3
 */

object TestTop_L2L3_AME extends App {
  val l2_banks = 8
  val l3_banks = 8
  val m_num = l2_banks

  val config = baseConfigAME(1, l2_banks, l3_banks, m_num).alterPartial({
    case L2ParamKey =>
      L2Param(
        clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
        echoField = Seq(DirtyField()),
        enablePerf = false
      )
    case HCCacheParamsKey =>
      HCCacheParameters(
        echoField = Seq(DirtyField()),
        enablePerf = false
      )
  })
  // ChiselDB.init(true)
  // Constantin.init(false)

  implicit val tlBundleParams: TLBundleParameters = TLBundleParameters(
    addressBits = 32,
    dataBits = 64,
    sourceBits = 4,
    sinkBits = 2,
    sizeBits = 4,
    echoFields = Nil,
    requestFields = Nil,
    responseFields = Nil,
    hasBCE = false
  )
  val top = DisableMonitors(p => LazyModule(new TestTop_AMU_L2_L3_RAM()(p, tlBundleParams)))(config)
  (new ChiselStage).execute(args, ChiselGeneratorAnnotation(() => top.module) +: TestTopAMEFirtoolOptions())
  // (new ChiselStage).execute(args, Seq(
  //   ChiselGeneratorAnnotation(() => top.module),
  //   // firrtl.stage.RunFirrtlTransformAnnotation(new firrtl.transforms.BlackBoxSourceHelper),
  //   // firrtl.options.TargetDirAnnotation("build"),
  //   firrtl.stage.OutputFileAnnotation("SimTop")

  // ))

  // ChiselDB.addToFileRegisters
  // Constantin.addToFileRegisters
  // FileRegisters.write("./build")
}
