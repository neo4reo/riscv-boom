// See LICENSE.Berkeley for license details.
// See LICENSE.SiFive for license details.

package boom


import Chisel._
import Chisel.ImplicitConversions._
import chisel3.core.withReset
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import freechips.rocketchip.util.property._
import chisel3.internal.sourceinfo.SourceInfo

//class FrontendReq(implicit p: Parameters) extends CoreBundle()(p) {
//  val pc = UInt(width = vaddrBitsExtended)
//  val speculative = Bool()
//}

//class FrontendExceptions extends Bundle {
//  val pf = new Bundle {
//    val inst = Bool()
//  }
//  val ae = new Bundle {
//    val inst = Bool()
//  }
//}
//
//class FrontendResp(implicit p: Parameters) extends CoreBundle()(p) {
//  val btb = new BTBResp
//  val pc = UInt(width = vaddrBitsExtended)  // ID stage PC
//  val data = UInt(width = fetchWidth * coreInstBits)
//  val mask = Bits(width = fetchWidth)
//  val xcpt = new FrontendExceptions
//  val replay = Bool()
//}
//
//class FrontendPerfEvents extends Bundle {
//  val acquire = Bool()
//  val tlbMiss = Bool()
//}
//

class BoomFrontendIO(implicit p: Parameters) extends BoomBundle()(p)
{
   // Give the backend a packet of instructions.
   val fetchpacket       = new DecoupledIO(new FetchBundle).flip

   val br_unit           = new BranchUnitResp().asOutput
   val get_pc            = new GetPCFromFtqIO().flip

   val sfence            = Valid(new SFenceReq)

   // sfence needs to steal the TLB CAM part.
   // TODO redudcant with above sfenceReq
   val sfence_take_pc    = Bool(OUTPUT)
   val sfence_addr       = UInt(OUTPUT, vaddrBits+1)


   val commit            = Valid(UInt(width=ftqSz.W))
   val flush_info        = Valid(new FlushSignals())
   val flush_take_pc     = Bool(OUTPUT)
   val flush_pc          = UInt(OUTPUT, vaddrBits+1) // TODO rename; no longer catch-all flush_pc
   val flush_icache      = Bool(OUTPUT)

   val com_ftq_idx       = UInt(OUTPUT, log2Up(ftqSz)) // ROB tells us the commit pointer so we can read out the PC.
   val com_fetch_pc      = UInt(INPUT, vaddrBitsExtended) // tell CSRFile the fetch-pc at the FTQ head.

   // bpd pipeline
   // should I take in the entire rob.io.flush?
   val flush             = Bool(OUTPUT) // pipeline flush from ROB TODO CODEREVIEW (redudant with fe_clear?)
   val clear_fetchbuffer = Bool(OUTPUT) // pipeline redirect (rob-flush, sfence request, branch mispredict)

   val status_prv        = UInt(OUTPUT, width = freechips.rocketchip.rocket.PRV.SZ)
   val status_debug      = Bool(OUTPUT)

   val perf              = new FrontendPerfEvents().asInput
   val tsc_reg           = UInt(OUTPUT, xLen)
}

class BoomFrontend(val icacheParams: ICacheParams, hartid: Int)(implicit p: Parameters) extends LazyModule {
  lazy val module = new BoomFrontendModule(this)
  val icache = LazyModule(new freechips.rocketchip.rocket.ICache(icacheParams, hartid))
  val masterNode = icache.masterNode
  val slaveNode = icache.slaveNode
}

class BoomFrontendBundle(val outer: BoomFrontend) extends CoreBundle()(outer.p)
    with HasExternallyDrivenTileConstants {
  val cpu = new BoomFrontendIO().flip
  val ptw = new TLBPTWIO()
  val errors = new ICacheErrors
}

class BoomFrontendModule(outer: BoomFrontend) extends LazyModuleImp(outer)
    with HasCoreParameters
    with HasL1ICacheParameters {
  val io = IO(new BoomFrontendBundle(outer))
  implicit val edge = outer.masterNode.edges.out(0)
  require(fetchWidth*coreInstBytes == outer.icacheParams.fetchBytes)

  val icache = outer.icache.module
  val tlb = Module(new TLB(true, log2Ceil(fetchBytes), nTLBEntries))
  // TODO rename (it's the controller unit?).
  val fetch_unit = Module(new FetchUnit(fetchWidth))
  val bpd_stage = Module(new BranchPredictionStage(fetchWidth))


  val s0_pc = Wire(UInt(INPUT, width = vaddrBitsExtended))
  val s0_valid = fetch_unit.io.imem_req.valid || fetch_unit.io.imem_resp.ready
  val s1_valid = RegNext(s0_valid)
  val s1_pc = Reg(UInt(width=vaddrBitsExtended))
  val s1_speculative = Reg(Bool())
  val s2_valid = RegInit(false.B)
  val s2_pc = RegInit(t = UInt(width = vaddrBitsExtended), alignPC(io.reset_vector))
  val s2_btb_resp_valid = if (usingBTB) Reg(Bool()) else false.B
  val s2_btb_resp_bits = Reg(new BTBResp)
  val s2_btb_taken = s2_btb_resp_valid && s2_btb_resp_bits.taken
  val s2_tlb_resp = Reg(tlb.io.resp)
  val s2_xcpt = s2_tlb_resp.ae.inst || s2_tlb_resp.pf.inst
  val s2_speculative = Reg(init=Bool(false))
  val s2_partial_insn_valid = RegInit(false.B)
  val s2_partial_insn = Reg(UInt(width = coreInstBits))
  val wrong_path = Reg(Bool())

  val s1_base_pc = ~(~s1_pc | (fetchBytes - 1))
  val ntpc = s1_base_pc + fetchBytes.U
  val predicted_npc = Wire(init = ntpc)
  val predicted_taken = Wire(init = Bool(false))

  val s2_replay = Wire(Bool())
  s2_replay := (s2_valid && !fetch_unit.io.imem_resp.fire()) || RegNext(s2_replay && !s0_valid, true.B)
  val npc = Mux(s2_replay, s2_pc, predicted_npc)

  s1_pc := s0_pc
  // consider RVC fetches across blocks to be non-speculative if the first
  // part was non-speculative
  val s0_speculative =
    if (usingCompressed) s1_speculative || s2_valid && !s2_speculative || predicted_taken
    else Bool(true)
  s1_speculative := Mux(fetch_unit.io.imem_req.valid, fetch_unit.io.imem_req.bits.speculative, Mux(s2_replay, s2_speculative, s0_speculative))

  val s2_redirect = Wire(init = fetch_unit.io.imem_req.valid)
  s2_valid := false
  when (!s2_replay) {
    s2_valid := !s2_redirect
    s2_pc := s1_pc
    s2_speculative := s1_speculative
    s2_tlb_resp := tlb.io.resp
  }

  io.ptw <> tlb.io.ptw
  tlb.io.req.valid := !s2_replay
  tlb.io.req.bits.vaddr := s1_pc
  tlb.io.req.bits.passthrough := Bool(false)
  tlb.io.req.bits.sfence := io.cpu.sfence
  tlb.io.req.bits.size := log2Ceil(coreInstBytes*fetchWidth)

  icache.io.hartid := io.hartid
  icache.io.req.valid := s0_valid
  icache.io.req.bits.addr := s0_pc
  icache.io.invalidate := io.cpu.flush_icache
  icache.io.s1_paddr := tlb.io.resp.paddr
  icache.io.s2_vaddr := s2_pc
  icache.io.s1_kill := s2_redirect || tlb.io.resp.miss || s2_replay
  icache.io.s2_kill := s2_speculative && !s2_tlb_resp.cacheable || s2_xcpt
  icache.io.s2_prefetch := s2_tlb_resp.prefetchable

  s0_pc := alignPC(Mux(fetch_unit.io.imem_req.valid, fetch_unit.io.imem_req.bits.pc, npc))
  fetch_unit.io.imem_resp.valid := RegNext(s1_valid) && s2_valid && (icache.io.resp.valid || !s2_tlb_resp.miss && icache.io.s2_kill)
  fetch_unit.io.imem_resp.bits.pc := s2_pc

  fetch_unit.io.imem_resp.bits.data := icache.io.resp.bits.data
  fetch_unit.io.imem_resp.bits.mask := UInt((1 << fetchWidth)-1) << s2_pc.extract(log2Ceil(fetchWidth)+log2Ceil(coreInstBytes)-1, log2Ceil(coreInstBytes))
  fetch_unit.io.imem_resp.bits.replay := icache.io.resp.bits.replay || icache.io.s2_kill && !icache.io.resp.valid && !s2_xcpt
  fetch_unit.io.imem_resp.bits.btb := s2_btb_resp_bits
  fetch_unit.io.imem_resp.bits.btb.taken := s2_btb_taken
  fetch_unit.io.imem_resp.bits.xcpt := s2_tlb_resp
  when (icache.io.resp.valid && icache.io.resp.bits.ae) { fetch_unit.io.imem_resp.bits.xcpt.ae.inst := true }

  //-------------------------------------------------------------
  // **** Fetch Controller ****
  //-------------------------------------------------------------

   fetch_unit.io.br_unit           := io.cpu.br_unit
   fetch_unit.io.tsc_reg           := io.cpu.tsc_reg

   fetch_unit.io.f2_btb_resp       := bpd_stage.io.f2_btb_resp
   fetch_unit.io.f3_bpd_resp       := bpd_stage.io.f3_bpd_resp

   fetch_unit.io.clear_fetchbuffer := io.cpu.clear_fetchbuffer

   fetch_unit.io.sfence_take_pc    := io.cpu.sfence_take_pc
   fetch_unit.io.sfence_addr       := io.cpu.sfence_addr

   fetch_unit.io.flush_take_pc     := io.cpu.flush_take_pc
   fetch_unit.io.flush_pc          := io.cpu.flush_pc
   fetch_unit.io.com_ftq_idx       := io.cpu.com_ftq_idx

   fetch_unit.io.flush_info        := io.cpu.flush_info
   fetch_unit.io.commit            := io.cpu.commit

   io.cpu.get_pc <> fetch_unit.io.get_pc

   io.cpu.com_fetch_pc := fetch_unit.io.com_fetch_pc

   io.cpu.fetchpacket <> fetch_unit.io.fetchpacket

   //-------------------------------------------------------------
   // **** Branch Prediction ****
   //-------------------------------------------------------------

   bpd_stage.io.s0_req.valid := s0_valid
   bpd_stage.io.s0_req.bits.addr := s0_pc


   bpd_stage.io.f2_replay := s2_replay
   bpd_stage.io.f2_stall := !fetch_unit.io.imem_resp.ready
   bpd_stage.io.f3_stall := fetch_unit.io.f3_stall
   bpd_stage.io.f3_is_br := fetch_unit.io.f3_is_br
   bpd_stage.io.debug_imemresp_pc := fetch_unit.io.imem_resp.bits.pc

   bpd_stage.io.br_unit := io.cpu.br_unit
   bpd_stage.io.ftq_restore := fetch_unit.io.ftq_restore_history
   bpd_stage.io.redirect := fetch_unit.io.imem_req.valid

   bpd_stage.io.flush := io.cpu.flush

   bpd_stage.io.f2_valid := fetch_unit.io.imem_resp.valid
   bpd_stage.io.f2_redirect := fetch_unit.io.f2_redirect
   bpd_stage.io.f4_redirect := fetch_unit.io.f4_redirect
   bpd_stage.io.f4_taken := fetch_unit.io.f4_taken
   bpd_stage.io.fe_clear := fetch_unit.io.clear_fetchbuffer

   bpd_stage.io.f3_ras_update := fetch_unit.io.f3_ras_update
   bpd_stage.io.f3_btb_update := fetch_unit.io.f3_btb_update
   bpd_stage.io.bim_update    := fetch_unit.io.bim_update
   bpd_stage.io.bpd_update    := fetch_unit.io.bpd_update



   bpd_stage.io.status_prv    := io.cpu.status_prv
   bpd_stage.io.status_debug  := io.cpu.status_debug

  //-------------------------------------------------------------
  // performance events
  io.cpu.perf := icache.io.perf
  io.cpu.perf.tlbMiss := io.ptw.req.fire()
  io.errors := icache.io.errors

  def alignPC(pc: UInt) = ~(~pc | (coreInstBytes - 1))

  def ccover(cond: Bool, label: String, desc: String)(implicit sourceInfo: SourceInfo) =
    cover(cond, s"FRONTEND_$label", "Rocket;;" + desc)

   override val compileOptions = chisel3.core.ExplicitCompileOptions.NotStrict.copy(explicitInvalidate = true)
}

/** Mix-ins for constructing tiles that have an ICache-based pipeline frontend */
trait HasBoomICacheFrontend extends CanHavePTW { this: BaseTile =>
  val module: HasBoomICacheFrontendModule
  val frontend = LazyModule(new BoomFrontend(tileParams.icache.get, hartId))
  tlMasterXbar.node := frontend.masterNode
  connectTLSlave(frontend.slaveNode, tileParams.core.fetchBytes)
  nPTWPorts += 1
  nPTWPorts += 1 // boom -- needs an extra PTW port for its LSU.
}

trait HasBoomICacheFrontendModule extends CanHavePTWModule {
  val outer: HasBoomICacheFrontend
  ptwPorts += outer.frontend.module.io.ptw
}

