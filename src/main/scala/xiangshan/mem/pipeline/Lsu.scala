package xiangshan.mem.pipeline

import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import chisel3.util.experimental.BoringUtils
import xiangshan.backend.decode.XSTrap
import xiangshan.AddressSpace
import xiangshan.mem._
import xiangshan.mem.cache._
import bus.simplebus._

object LSUOpType {
  def lb   = "b000000".U
  def lh   = "b000001".U
  def lw   = "b000010".U
  def ld   = "b000011".U
  def lbu  = "b000100".U
  def lhu  = "b000101".U
  def lwu  = "b000110".U
  def ldu  = "b000111".U
  def sb   = "b001000".U
  def sh   = "b001001".U
  def sw   = "b001010".U
  def sd   = "b001011".U

  def lr      = "b100010".U
  def sc      = "b100011".U
  def amoswap = "b100001".U
  def amoadd  = "b100000".U
  def amoxor  = "b100100".U
  def amoand  = "b101100".U
  def amoor   = "b101000".U
  def amomin  = "b110000".U
  def amomax  = "b110100".U
  def amominu = "b111000".U
  def amomaxu = "b111100".U
  
  def isStore(func: UInt): Bool = func(3)
  def isAtom(func: UInt): Bool = func(5)

  def atomW = "010".U
  def atomD = "011".U
}

class LsPipelineBundle extends XSBundle {
  val vaddr = UInt(VAddrBits.W)
  val paddr = UInt(PAddrBits.W)
  val func = UInt(6.W)
  val mask = UInt(8.W)
  val data = UInt(XLEN.W)
  // val moqIdx = UInt(log2Up(MoqSize).W)
  val uop = new MicroOp

  val miss = Bool()
  val mmio = Bool()
  val rollback = Bool()

  val forwardMask = Vec(8, Bool())
  val forwardData = Vec(8, UInt(8.W))
}

class LoadForwardQueryIO extends XSBundle {
  val paddr = Output(UInt(PAddrBits.W))
  val mask = Output(UInt(8.W))
  val moqIdx = Output(UInt(MoqIdxWidth.W))
  val pc = Output(UInt(VAddrBits.W)) //for debug
  val valid = Output(Bool()) //for debug

  val forwardMask = Input(Vec(8, Bool()))
  val forwardData = Input(Vec(8, UInt(8.W)))
}

class LsuIO extends XSBundle {
  val ldin = Vec(2, Flipped(Decoupled(new ExuInput)))
  val stin = Vec(2, Flipped(Decoupled(new ExuInput)))
  val ldout = Vec(2, Decoupled(new ExuOutput))
  val stout = Vec(2, Decoupled(new ExuOutput))
  val redirect = Flipped(ValidIO(new Redirect))
  val rollback = Output(Valid(new Redirect))
  val tlbFeedback = Vec(exuParameters.LduCnt + exuParameters.LduCnt, ValidIO(new TlbFeedback))
  val mcommit = Flipped(Vec(CommitWidth, Valid(UInt(MoqIdxWidth.W))))
  val dp1Req = Vec(RenameWidth, Flipped(DecoupledIO(new MicroOp)))
  val moqIdxs = Output(Vec(RenameWidth, UInt(MoqIdxWidth.W)))
  val dcache = Flipped(new DcacheToLsuIO)
  val dtlb = new TlbRequestIO(DTLBWidth)
  val refill = Flipped(Valid(new DCacheStoreReq))
  val miss = Decoupled(new MissReqIO)
}

// 2l2s out of order lsu for XiangShan
class Lsu extends XSModule {
  override def toString: String = "Ldu"
  val io = IO(new LsuIO)

  io.dcache.refill <> io.refill

  val lsroq = Module(new Lsroq)
  val sbuffer = Module(new FakeSbuffer)

  lsroq.io.mcommit <> io.mcommit
  lsroq.io.dp1Req <> io.dp1Req
  lsroq.io.moqIdxs <> io.moqIdxs
  lsroq.io.brqRedirect := io.redirect
  io.rollback <> lsroq.io.rollback
  io.dcache.redirect := io.redirect

  lsroq.io.refill <> io.refill
  lsroq.io.refill.valid := false.B // TODO
  lsroq.io.miss <> io.miss

  def genWmask(addr: UInt, sizeEncode: UInt): UInt = {
    LookupTree(sizeEncode, List(
      "b00".U -> 0x1.U, //0001 << addr(2:0)
      "b01".U -> 0x3.U, //0011
      "b10".U -> 0xf.U, //1111
      "b11".U -> 0xff.U //11111111
    )) << addr(2, 0)
  }

  def genWdata(data: UInt, sizeEncode: UInt): UInt = {
    LookupTree(sizeEncode, List(
      "b00".U -> Fill(8, data(7, 0)),
      "b01".U -> Fill(4, data(15, 0)),
      "b10".U -> Fill(2, data(31, 0)),
      "b11".U -> data
    ))
  }

//-------------------------------------------------------
// Load Pipeline
//-------------------------------------------------------

  val l2_out = Wire(Vec(2, Decoupled(new LsPipelineBundle)))
  val l4_out = Wire(Vec(2, Decoupled(new LsPipelineBundle)))
  val l5_in  = Wire(Vec(2, Flipped(Decoupled(new LsPipelineBundle))))

  (0 until LoadPipelineWidth).map(i => {
    when (l2_out(i).valid) { XSDebug("L2_"+i+": pc 0x%x addr 0x%x -> 0x%x op %b data 0x%x mask %x\n", l2_out(i).bits.uop.cf.pc, l2_out(i).bits.vaddr, l2_out(i).bits.paddr, l2_out(i).bits.uop.ctrl.fuOpType, l2_out(i).bits.data, l2_out(i).bits.mask)}; 
    when (l4_out(i).valid) { XSDebug("L4_"+i+": pc 0x%x addr 0x%x -> 0x%x op %b data 0x%x mask %x\n", l4_out(i).bits.uop.cf.pc, l4_out(i).bits.vaddr, l4_out(i).bits.paddr, l4_out(i).bits.uop.ctrl.fuOpType, l4_out(i).bits.data, l4_out(i).bits.mask)}; 
    when (l5_in(i).valid)  { XSDebug("L5_"+i+": pc 0x%x addr 0x%x -> 0x%x op %b data 0x%x mask %x\n", l5_in(i).bits.uop.cf.pc,  l5_in(i).bits.vaddr , l5_in(i).bits.paddr , l5_in(i).bits.uop.ctrl.fuOpType , l5_in(i).bits.data,  l5_in(i).bits.mask )}; 
    XSDebug(l2_out(i).fire(), "load req: pc 0x%x addr 0x%x -> 0x%x op %b\n", l2_out(i).bits.uop.cf.pc, l2_out(i).bits.vaddr, l2_out(i).bits.paddr, l2_out(i).bits.uop.ctrl.fuOpType)
  })

//-------------------------------------------------------
// LD Pipeline Stage 2
// Generate addr, use addr to query DCache Tag and DTLB
//-------------------------------------------------------

  (0 until LoadPipelineWidth).map(i => {
    // l2_out is used to generate dcache req
    l2_out(i).bits := DontCare
    l2_out(i).bits.vaddr := io.ldin(i).bits.src1 + io.ldin(i).bits.uop.ctrl.imm
    l2_out(i).bits.paddr := io.dtlb.resp(i).bits.paddr
    l2_out(i).bits.uop := io.ldin(i).bits.uop
    l2_out(i).bits.mask := genWmask(l2_out(i).bits.vaddr, io.ldin(i).bits.uop.ctrl.fuOpType(1,0))
    l2_out(i).valid := io.ldin(i).valid
    l2_out(i).ready := io.dcache.load(i).req.ready
    io.ldin(i).ready := l2_out(i).ready
  })

  // send req to dtlb
  (0 until LoadPipelineWidth).map(i => {
    io.dtlb.req(i).valid := l2_out(i).valid
    io.dtlb.req(i).bits.vaddr := l2_out(i).bits.vaddr(VAddrBits-1, 0)
    io.dtlb.req(i).bits.idx := l2_out(i).bits.uop.roqIdx
    io.dtlb.req(i).bits.cmd := SimpleBusCmd.read
  })
  
  // send result to dcache
  (0 until LoadPipelineWidth).map(i => {
    io.dcache.load(i).req.valid := io.dtlb.resp(i).valid && !io.dtlb.resp(i).bits.miss
    io.dcache.load(i).req.bits.vaddr := l2_out(i).bits.vaddr
    io.dcache.load(i).req.bits.paddr := io.dtlb.resp(i).bits.paddr
    io.dcache.load(i).req.bits.miss := io.dtlb.resp(i).bits.miss
    io.dcache.load(i).req.bits.user := DontCare
    io.dcache.load(i).req.bits.user.uop := l2_out(i).bits.uop
    io.dcache.load(i).req.bits.user.mmio := AddressSpace.isMMIO(io.dcache.load(i).req.bits.paddr)
    io.dcache.load(i).req.bits.user.mask := l2_out(i).bits.mask
  })



  val l2_tlbFeedback = (0 until LoadPipelineWidth).map(_ => Wire(new TlbFeedback))
  for((fb, i) <- l2_tlbFeedback.zipWithIndex){
    fb.hit := !io.dtlb.resp(i).bits.miss
    fb.roqIdx := l2_out(i).bits.uop.roqIdx
  }

//-------------------------------------------------------
// LD Pipeline Stage 3
// Compare tag, use addr to query DCache Data
//-------------------------------------------------------

  val l3_tlbFeedback = l2_tlbFeedback.map(RegNext(_))
  val l3_valid = l2_out.map(x => RegNext(x.fire(), false.B))
  for(i <- 0 until LoadPipelineWidth){
    io.tlbFeedback(i).valid := l3_valid(i)
    io.tlbFeedback(i).bits := l3_tlbFeedback(i)
  }


// Done in Dcache

//-------------------------------------------------------
// LD Pipeline Stage 4
// Dcache return result, do tag ecc check and forward check
//-------------------------------------------------------

  // result from dcache
  (0 until LoadPipelineWidth).map(i => {
    io.dcache.load(i).resp.ready := true.B
    l4_out(i).bits := DontCare
    l4_out(i).bits.paddr := io.dcache.load(i).resp.bits.paddr
    l4_out(i).bits.data := io.dcache.load(i).resp.bits.data
    l4_out(i).bits.uop := io.dcache.load(i).resp.bits.user.uop
    l4_out(i).bits.mmio := io.dcache.load(i).resp.bits.user.mmio
    l4_out(i).bits.mask := io.dcache.load(i).resp.bits.user.mask
    l4_out(i).valid := io.dcache.load(i).resp.valid
  })

  // Store addr forward match
  // If match, get data / fmask from store queue / store buffer

  (0 until LoadPipelineWidth).map(i => {

    lsroq.io.forward(i).paddr := l4_out(i).bits.paddr
    lsroq.io.forward(i).mask := io.dcache.load(i).resp.bits.user.mask
    lsroq.io.forward(i).moqIdx := l4_out(i).bits.uop.moqIdx
    lsroq.io.forward(i).pc := l4_out(i).bits.uop.cf.pc
    lsroq.io.forward(i).valid := l4_out(i).valid
    
    sbuffer.io.forward(i).paddr := l4_out(i).bits.paddr
    sbuffer.io.forward(i).mask := io.dcache.load(i).resp.bits.user.mask
    sbuffer.io.forward(i).moqIdx := l4_out(i).bits.uop.moqIdx
    sbuffer.io.forward(i).pc := l4_out(i).bits.uop.cf.pc
    sbuffer.io.forward(i).valid := l4_out(i).valid
    
    val forwardVec = WireInit(lsroq.io.forward(i).forwardData)
    val forwardMask = WireInit(lsroq.io.forward(i).forwardMask)
    (0 until XLEN/8).map(j => {
      when(sbuffer.io.forward(i).forwardMask(j)){
        forwardMask(j) := true.B
        forwardVec(j) := sbuffer.io.forward(i).forwardData(j)
      }
    // generate XLEN/8 Muxs
    })
    
    l4_out(i).bits.forwardMask := forwardMask
    l4_out(i).bits.forwardData := forwardVec
  })
  
  (0 until LoadPipelineWidth).map(i => {
    PipelineConnect(l4_out(i), l5_in(i), io.ldout(i).fire(), l5_in(i).valid && l5_in(i).bits.uop.needFlush(io.redirect))
  })

//-------------------------------------------------------
// LD Pipeline Stage 5
// Do data ecc check, merge result and write back to LS ROQ
// If cache hit, return writeback result to CDB
//-------------------------------------------------------

  val loadWriteBack = (0 until LoadPipelineWidth).map(i => { l5_in(i).fire() })
  val hitLoadOut = (0 until LoadPipelineWidth).map(_ => Wire(Decoupled(new ExuOutput)))
  (0 until LoadPipelineWidth).map(i => {
    // data merge
    val rdata = VecInit((0 until 8).map(j => {
      Mux(l5_in(i).bits.forwardMask(j), 
        l5_in(i).bits.forwardData(j), 
        l5_in(i).bits.data(8*(j+1)-1, 8*j)
      )
    })).asUInt
    val func = l5_in(i).bits.uop.ctrl.fuOpType
    val raddr = l5_in(i).bits.paddr
    val rdataSel = LookupTree(raddr(2, 0), List(
      "b000".U -> rdata(63, 0),
      "b001".U -> rdata(63, 8),
      "b010".U -> rdata(63, 16),
      "b011".U -> rdata(63, 24),
      "b100".U -> rdata(63, 32),
      "b101".U -> rdata(63, 40),
      "b110".U -> rdata(63, 48),
      "b111".U -> rdata(63, 56)
    ))
    val rdataPartialLoad = LookupTree(func, List(
        LSUOpType.lb   -> SignExt(rdataSel(7, 0) , XLEN),
        LSUOpType.lh   -> SignExt(rdataSel(15, 0), XLEN),
        LSUOpType.lw   -> SignExt(rdataSel(31, 0), XLEN),
        LSUOpType.ld   -> SignExt(rdataSel(63, 0), XLEN),
        LSUOpType.lbu  -> ZeroExt(rdataSel(7, 0) , XLEN),
        LSUOpType.lhu  -> ZeroExt(rdataSel(15, 0), XLEN),
        LSUOpType.lwu  -> ZeroExt(rdataSel(31, 0), XLEN),
        LSUOpType.ldu  -> ZeroExt(rdataSel(63, 0), XLEN)
    ))

    // ecc check
    // TODO

    // if hit, writeback result to CDB
    // val ldout = Vec(2, Decoupled(new ExuOutput))
    // when io.loadIn(i).fire() && !io.io.loadIn(i).miss, commit load to cdb
    hitLoadOut(i).bits.uop := l5_in(i).bits.uop
    hitLoadOut(i).bits.data := rdataPartialLoad
    hitLoadOut(i).bits.redirectValid := false.B
    hitLoadOut(i).bits.redirect := DontCare
    hitLoadOut(i).bits.brUpdate := DontCare
    hitLoadOut(i).bits.debug.isMMIO := l5_in(i).bits.mmio
    hitLoadOut(i).valid := l5_in(i).valid
    XSDebug(hitLoadOut(i).fire(), "load writeback: pc %x data %x (%x + %x(%b))\n", 
      hitLoadOut(i).bits.uop.cf.pc, rdataPartialLoad, l5_in(i).bits.data, 
      l5_in(i).bits.forwardData.asUInt, l5_in(i).bits.forwardMask.asUInt
    )
    
    // writeback to LSROQ
    // Current dcache use MSHR

    lsroq.io.loadIn(i).bits := l5_in(i).bits
    lsroq.io.loadIn(i).bits.data := rdataPartialLoad // for debug
    lsroq.io.loadIn(i).valid := loadWriteBack(i)

    // pipeline control
    l5_in(i).ready := io.ldout(i).ready

    lsroq.io.ldout(i).ready := false.B // TODO
    // TODO: writeback missed loads
  })

//-------------------------------------------------------
// Store Pipeline
//-------------------------------------------------------

  val s2_out = Wire(Vec(2, Decoupled(new LsPipelineBundle)))
  val s3_in  = Wire(Vec(2, Flipped(Decoupled(new LsPipelineBundle))))

  (0 until StorePipelineWidth).map(i => {
    when (s2_out(i).valid) { XSDebug("S2_"+i+": pc 0x%x addr 0x%x -> 0x%x op %b data 0x%x mask %x\n", s2_out(i).bits.uop.cf.pc, s2_out(i).bits.vaddr, s2_out(i).bits.paddr, s2_out(i).bits.uop.ctrl.fuOpType, s2_out(i).bits.data, s2_out(i).bits.mask)}; 
    when (s3_in(i).valid ) { XSDebug("S3_"+i+": pc 0x%x addr 0x%x -> 0x%x op %b data 0x%x mask %x\n", s3_in(i).bits.uop.cf.pc , s3_in(i).bits.vaddr , s3_in(i).bits.paddr , s3_in(i).bits.uop.ctrl.fuOpType , s3_in(i).bits.data,  s3_in(i).bits.mask )}; 
    // when (s4_in(i).valid ) { printf("S4_"+i+": pc 0x%x addr 0x%x -> 0x%x op %b data 0x%x\n", s4_in(i).bits.uop.cf.pc , s4_in(i).bits.vaddr , s4_in(i).bits.paddr , s4_in(i).bits.uop.ctrl.fuOpType , s4_in(i).bits.data )}; 
    XSDebug(s2_out(i).fire(), "store req: pc 0x%x addr 0x%x -> 0x%x op %b data 0x%x\n", s2_out(i).bits.uop.cf.pc, s2_out(i).bits.vaddr, s2_out(i).bits.paddr, s2_out(i).bits.uop.ctrl.fuOpType, s2_out(i).bits.data)
  })
  
  //-------------------------------------------------------
  // ST Pipeline Stage 2
  // Generate addr, use addr to query DTLB
  //-------------------------------------------------------
  
  // send req to dtlb
  val saddr = VecInit((0 until StorePipelineWidth).map(i => {
    io.stin(i).bits.src1 + io.stin(i).bits.uop.ctrl.imm
  }))

  (0 until StorePipelineWidth).map(i => {
    io.dtlb.req(LoadPipelineWidth + i).bits.vaddr := saddr(i)(VAddrBits-1, 0)
    io.dtlb.req(LoadPipelineWidth + i).valid := io.stin(i).valid
    io.dtlb.req(LoadPipelineWidth + i).bits.idx := io.stin(i).bits.uop.roqIdx
    io.dtlb.req(LoadPipelineWidth + i).bits.cmd := SimpleBusCmd.write
  })

  (0 until StorePipelineWidth).map(i => {
    s2_out(i).bits := DontCare
    s2_out(i).bits.vaddr := saddr(i)
    s2_out(i).bits.paddr := io.dtlb.resp(LoadPipelineWidth + i).bits.paddr
    s2_out(i).bits.data := genWdata(io.stin(i).bits.src2, io.stin(i).bits.uop.ctrl.fuOpType(1,0))
    s2_out(i).bits.uop := io.stin(i).bits.uop
    s2_out(i).bits.miss := io.dtlb.resp(LoadPipelineWidth + i).bits.miss
    s2_out(i).bits.mask := genWmask(s2_out(i).bits.vaddr, io.stin(i).bits.uop.ctrl.fuOpType(1,0))
    s2_out(i).valid := io.stin(i).valid && !io.dtlb.resp(LoadPipelineWidth + i).bits.miss
    io.stin(i).ready := s2_out(i).ready
  })

  (0 until StorePipelineWidth).map(i =>{
    PipelineConnect(s2_out(i), s3_in(i), true.B, s3_in(i).valid && s3_in(i).bits.uop.needFlush(io.redirect))
  })



//-------------------------------------------------------
// ST Pipeline Stage 3
// Write paddr to LSROQ
//-------------------------------------------------------

  // Send TLB feedback to store issue queue
  (0 until StorePipelineWidth).foreach(i => {
    io.tlbFeedback(LoadPipelineWidth + i).valid := s3_in(i).fire()
    io.tlbFeedback(LoadPipelineWidth + i).bits.hit := !s3_in(i).bits.miss
    io.tlbFeedback(LoadPipelineWidth + i).bits.roqIdx := s3_in(i).bits.uop.roqIdx
  })

  // get paddr from dtlb, check if rollback is needed
  // writeback store inst to lsroq
  (0 until StorePipelineWidth).map(i => {
    // writeback to LSROQ
    s3_in(i).ready := true.B
    lsroq.io.storeIn(i).bits := s3_in(i).bits
    lsroq.io.storeIn(i).bits.mmio := AddressSpace.isMMIO(s3_in(i).bits.paddr)
    lsroq.io.storeIn(i).valid := s3_in(i).fire()
  })

//-------------------------------------------------------
// ST Pipeline Stage 4
// Store writeback, send store request to store buffer
//-------------------------------------------------------

  // LSROQ to store buffer
  (0 until StorePipelineWidth).map(i => {
    lsroq.io.sbuffer(i) <> sbuffer.io.in(i)
  })
  
  // Writeback to CDB
  // (0 until LoadPipelineWidth).map(i => {
  //   io.ldout(i) <> hitLoadOut(i)
  // })
  (0 until StorePipelineWidth).map(i => {
    io.stout(i) <> lsroq.io.stout(i)
  })

  (0 until 2).map(i => {
    val cdbArb = Module(new Arbiter(new ExuOutput, 2))
    io.ldout(i) <> cdbArb.io.out
    hitLoadOut(i) <> cdbArb.io.in(0)
    lsroq.io.ldout(i) <> cdbArb.io.in(1) // missLoadOut
  })

//-------------------------------------------------------
// ST Pipeline Async Stage 1
// Read paddr from store buffer, query DTAG in DCache
//-------------------------------------------------------

  sbuffer.io.dcache <> io.dcache.store

//-------------------------------------------------------
// ST Pipeline Async Stage 2
// DTAG compare, write data to DCache
//-------------------------------------------------------

// Done in DCache

//-------------------------------------------------------
// ST Pipeline Async Stage 2
// DCache miss / Shared cache wirte
//-------------------------------------------------------

// update store buffer according to store fill buffer

}