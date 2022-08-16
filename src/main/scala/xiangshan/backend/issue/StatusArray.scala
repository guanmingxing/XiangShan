/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.backend.issue

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.backend.rob.RobPtr
import xiangshan.mem.{SqPtr, MemWaitUpdateReq}

class StatusArrayUpdateIO(params: RSParams)(implicit p: Parameters) extends Bundle {
  val enable = Input(Bool())
  // should be one-hot
  val addr = Input(UInt(params.numEntries.W))
  val data = Input(new StatusEntry(params))

  def isLegal: Bool = PopCount(addr.asBools) === 0.U
}

class StatusEntry(params: RSParams)(implicit p: Parameters) extends XSBundle {
  // states
  val valid = Bool()
  val scheduled = Bool()
  val blocked = Bool()
  val credit = UInt(4.W)
  val srcState = Vec(params.numSrc, Bool())
  val midState = Bool()
  // data
  val psrc = Vec(params.numSrc, UInt(params.dataIdBits.W))
  val srcType = Vec(params.numSrc, SrcType())
  val robIdx = new RobPtr
  val waitForSqIdx = new SqPtr // generated by store data valid check
  val waitForRobIdx = new RobPtr // generated by store set
  val waitForStoreData = Bool()
  val strictWait = Bool()
  val sqIdx = new SqPtr
  // misc
  val isFirstIssue = Bool()

  def canIssue: Bool = {
    val scheduledCond = if (params.needScheduledBit) !scheduled else true.B
    val blockedCond = if (params.checkWaitBit) !blocked else true.B
    val checkedSrcState = if (params.numSrc > 2) srcState.take(2) else srcState
    val midStateReady = if (params.hasMidState) srcState.last && midState else false.B
    (VecInit(checkedSrcState).asUInt.andR && scheduledCond || midStateReady) && blockedCond
  }

  def allSrcReady: Bool = {
    val midStateReady = if (params.hasMidState) srcState.last && midState else false.B
    srcState.asUInt.andR || midStateReady
  }

  override def toPrintable: Printable = {
    p"$valid, $scheduled, ${Binary(srcState.asUInt)}, $psrc, $robIdx"
  }
}

class StatusArray(params: RSParams)(implicit p: Parameters) extends XSModule
  with HasCircularQueuePtrHelper {
  val io = IO(new Bundle {
    val redirect = Flipped(ValidIO(new Redirect))
    // current status
    val isValid = Output(UInt(params.numEntries.W))
    val isValidNext = Output(UInt(params.numEntries.W))
    val canIssue = Output(UInt(params.numEntries.W))
    val flushed = Output(UInt(params.numEntries.W))
    // enqueue, dequeue, wakeup, flush
    val update = Vec(params.numEnq, new StatusArrayUpdateIO(params))
    val wakeup = Vec(params.allWakeup, Flipped(ValidIO(new MicroOp)))
    val wakeupMatch = Vec(params.numEntries, Vec(params.numSrc, Output(UInt(params.allWakeup.W))))
    val issueGranted = Vec(params.numSelect, Flipped(ValidIO(UInt(params.numEntries.W))))
    // TODO: if more info is needed, put them in a bundle
    val isFirstIssue = Vec(params.numSelect, Output(Bool()))
    val allSrcReady = Vec(params.numSelect, Output(Bool()))
    val updateMidState = Input(UInt(params.numEntries.W))
    val deqRespWidth = if (params.hasFeedback) params.numDeq * 2 else params.numDeq + params.numDeq + 1
    val deqResp = Vec(deqRespWidth, Flipped(ValidIO(new Bundle {
      val rsMask = UInt(params.numEntries.W)
      val success = Bool()
      val resptype = RSFeedbackType() // update credit if needs replay
      val dataInvalidSqIdx = new SqPtr
    })))
    val stIssuePtr = if (params.checkWaitBit) Input(new SqPtr()) else null
    val memWaitUpdateReq = if (params.checkWaitBit) Flipped(new MemWaitUpdateReq) else null
  })

  val statusArray = Reg(Vec(params.numEntries, new StatusEntry(params)))
  val statusArrayNext = WireInit(statusArray)
  statusArray := statusArrayNext
  when (reset.asBool) {
    statusArray.map(_.valid := false.B)
  }

  // instruction is ready for issue
  val readyVec = VecInit(statusArray.map(_.canIssue))
  val readyVecNext = VecInit(statusArrayNext.map(_.canIssue))

  // update srcState when enqueue, wakeup
  // For better timing, we use different conditions for data write and srcState update
  // srcInfo: (psrc, srcType)
  def wakeupMatch(srcInfo: (UInt, UInt)): (Bool, UInt) = {
    val (stateMatchVec, dataMatchVec) = io.wakeup.map(w => {
      val (stateMatch, dataMatch) = w.bits.wakeup(Seq(srcInfo), params.exuCfg.get).head
      (w.valid && stateMatch, w.valid && dataMatch)
    }).unzip
    val stateMatch = VecInit(stateMatchVec).asUInt.orR
    val dataMatch = VecInit(dataMatchVec).asUInt
    XSError(PopCount(dataMatchVec) > 1.U, p"matchVec ${Binary(dataMatch)} should be one-hot\n")
    (stateMatch, dataMatch)
  }

  def deqRespSel(i: Int) : (Bool, Bool, UInt, SqPtr) = {
    val mask = VecInit(io.deqResp.map(resp => resp.valid && resp.bits.rsMask(i)))
    XSError(PopCount(mask) > 1.U, p"feedbackVec ${Binary(mask.asUInt)} should be one-hot\n")
    val deqValid = mask.asUInt.orR
    val successVec = io.deqResp.map(_.bits.success)
    val respTypeVec = io.deqResp.map(_.bits.resptype)
    val dataInvalidSqIdxVec = io.deqResp.map(_.bits.dataInvalidSqIdx)
    (deqValid, ParallelMux(mask, successVec), Mux1H(mask, respTypeVec), Mux1H(mask, dataInvalidSqIdxVec))
  }

  def enqUpdate(i: Int): (Bool, StatusEntry) = {
    val updateVec = VecInit(io.update.map(u => u.enable && u.addr(i)))
    val updateStatus = Mux1H(updateVec, io.update.map(_.data))
    XSError(PopCount(updateVec) > 1.U, "should not update the same entry\n")
    (updateVec.asUInt.orR, updateStatus)
  }

  val flushedVec = Wire(Vec(params.numEntries, Bool()))

  val (updateValid, updateVal) = statusArray.indices.map(enqUpdate).unzip
  val deqResp = statusArray.indices.map(deqRespSel)

  val is_issued = Wire(Vec(params.numEntries, Bool()))
  for (((status, statusNext), i) <- statusArray.zip(statusArrayNext).zipWithIndex) {
    // valid: when the entry holds a valid instruction, mark it true.
    // Set when (1) not (flushed or deq); AND (2) update.
    val realValid = updateValid(i) || status.valid
    val (deqRespValid, deqRespSucc, deqRespType, deqRespDataInvalidSqIdx) = deqResp(i)
    val isFlushed = statusNext.robIdx.needFlush(io.redirect)
    flushedVec(i) := RegNext(realValid && isFlushed) || deqRespSucc
    statusNext.valid := realValid && !(isFlushed || deqRespSucc)
    XSError(updateValid(i) && status.valid, p"should not update a valid entry $i\n")
    XSError(deqRespValid && !realValid, p"should not deq an invalid entry $i\n")
    if (params.hasFeedback) {
      XSError(deqRespValid && !statusArray(i).scheduled, p"should not deq an un-scheduled entry $i\n")
    }

    // scheduled: when the entry is scheduled for issue, mark it true.
    // Set when (1) scheduled for issue; (2) enq blocked.
    // Reset when (1) deq is not granted (it needs to be scheduled again); (2) only one credit left.
    val hasIssued = VecInit(io.issueGranted.map(iss => iss.valid && iss.bits(i))).asUInt.orR
    val deqNotGranted = deqRespValid && !deqRespSucc
    statusNext.scheduled := false.B
    if (params.needScheduledBit) {
      // An entry keeps in the scheduled state until its credit comes to zero or deqFailed.
      val noCredit = status.valid && status.credit === 1.U
      val keepScheduled = status.scheduled && !deqNotGranted && !noCredit
      // updateValid may arrive at the same cycle as hasIssued.
      statusNext.scheduled := hasIssued || Mux(updateValid(i), updateVal(i).scheduled, keepScheduled)
    }
    XSError(hasIssued && !realValid, p"should not issue an invalid entry $i\n")
    is_issued(i) := status.valid && hasIssued

    // blocked: indicate whether the entry is blocked for issue until certain conditions meet.
    statusNext.blocked := false.B
    if (params.checkWaitBit) {
      val blockNotReleased = isAfter(statusNext.sqIdx, io.stIssuePtr)
      val storeAddrWaitforIsIssuing = VecInit((0 until StorePipelineWidth).map(i => {
        io.memWaitUpdateReq.staIssue(i).valid &&
        io.memWaitUpdateReq.staIssue(i).bits.uop.robIdx.value === statusNext.waitForRobIdx.value
      })).asUInt.orR && !statusNext.waitForStoreData && !statusNext.strictWait // is waiting for store addr ready
      val storeDataWaitforIsIssuing = VecInit((0 until StorePipelineWidth).map(i => {
        io.memWaitUpdateReq.stdIssue(i).valid &&
        io.memWaitUpdateReq.stdIssue(i).bits.uop.sqIdx.value === statusNext.waitForSqIdx.value
      })).asUInt.orR && statusNext.waitForStoreData
      statusNext.blocked := Mux(updateValid(i), updateVal(i).blocked, status.blocked) &&
        !storeAddrWaitforIsIssuing &&
        !storeDataWaitforIsIssuing &&
        blockNotReleased
      when(updateValid(i)) {
        statusNext.strictWait := updateVal(i).strictWait
        statusNext.waitForStoreData := updateVal(i).waitForStoreData
        statusNext.waitForRobIdx := updateVal(i).waitForRobIdx
        assert(updateVal(i).waitForStoreData === false.B)
      }
      when (deqNotGranted && deqRespType === RSFeedbackType.dataInvalid) {
        statusNext.blocked := true.B
        statusNext.waitForSqIdx := deqRespDataInvalidSqIdx
        statusNext.waitForStoreData := true.B
        XSError(status.valid && !isAfter(status.sqIdx, RegNext(RegNext(io.stIssuePtr))),
          "Previous store instructions are all issued. Should not trigger dataInvalid.\n")
      }
    }

    // credit: the number of cycles this entry needed until it can be scheduled
    val creditStep = Mux(status.credit > 0.U, status.credit - 1.U, status.credit)
    statusNext.credit := Mux(updateValid(i), updateVal(i).credit, creditStep)
    XSError(status.valid && status.credit > 0.U && !status.scheduled,
      p"instructions $i with credit ${status.credit} must not be scheduled\n")

    // srcState: indicate whether the operand is ready for issue
    val (stateWakeupEn, dataWakeupEnVec) = statusNext.psrc.zip(statusNext.srcType).map(wakeupMatch).unzip
    io.wakeupMatch(i) := dataWakeupEnVec.map(en => Mux(updateValid(i) || status.valid, en, 0.U))
    // For best timing of srcState, we don't care whether the instruction is valid or not.
    // We also don't care whether the instruction can really enqueue.
    statusNext.srcState := VecInit(status.srcState.zip(updateVal(i).srcState).zip(stateWakeupEn).map {
      // When the instruction enqueues, we always use the wakeup result.
      case ((current, update), wakeup) => wakeup || Mux(updateValid(i), update, current)
    })

    // midState: reset when enqueue; set when receiving feedback
    statusNext.midState := !updateValid(i) && (io.updateMidState(i) || status.midState)

    // static data fields (only updated when instructions enqueue)
    statusNext.psrc := Mux(updateValid(i), updateVal(i).psrc, status.psrc)
    statusNext.srcType := Mux(updateValid(i), updateVal(i).srcType, status.srcType)
    statusNext.robIdx := Mux(updateValid(i), updateVal(i).robIdx, status.robIdx)
    statusNext.sqIdx := Mux(updateValid(i), updateVal(i).sqIdx, status.sqIdx)

    // isFirstIssue: indicate whether the entry has been issued before
    // When the entry is not granted to issue, set isFirstIssue to false.B
    statusNext.isFirstIssue := Mux(hasIssued, false.B, updateValid(i) || status.isFirstIssue)

    XSDebug(status.valid, p"entry[$i]: $status\n")
  }

  io.isValid := VecInit(statusArray.map(_.valid)).asUInt
  io.isValidNext := VecInit(statusArrayNext.map(_.valid)).asUInt
  io.canIssue := VecInit(statusArrayNext.map(_.valid).zip(readyVecNext).map{ case (v, r) => RegNext(v && r) }).asUInt
  io.isFirstIssue := VecInit(io.issueGranted.map(iss => Mux1H(iss.bits, statusArray.map(_.isFirstIssue))))
  io.allSrcReady := VecInit(io.issueGranted.map(iss => Mux1H(iss.bits, statusArray.map(_.allSrcReady))))
  io.flushed := flushedVec.asUInt

  val validEntries = PopCount(statusArray.map(_.valid))
  XSPerfHistogram("valid_entries", validEntries, true.B, 0, params.numEntries, 1)
  for (i <- 0 until params.numSrc) {
    val waitSrc = statusArray.map(_.srcState).map(s => Cat(s.zipWithIndex.filter(_._2 != i).map(_._1)).andR && !s(i))
    val srcBlockIssue = statusArray.zip(waitSrc).map{ case (s, w) => s.valid && !s.scheduled && !s.blocked && w }
    XSPerfAccumulate(s"wait_for_src_$i", PopCount(srcBlockIssue))
    for (j <- 0 until params.allWakeup) {
      val wakeup_j_i = io.wakeupMatch.map(_(i)(j)).zip(statusArray.map(_.valid)).map(p => p._1 && p._2)
      XSPerfAccumulate(s"wakeup_${j}_$i", PopCount(wakeup_j_i).asUInt)
      val criticalWakeup = srcBlockIssue.zip(wakeup_j_i).map(x => x._1 && x._2)
      XSPerfAccumulate(s"critical_wakeup_${j}_$i", PopCount(criticalWakeup))
      // For FMAs only: critical_wakeup from fma instructions (to fma instructions)
      if (i == 2 && j < 2 * exuParameters.FmacCnt) {
        val isFMA = io.wakeup(j).bits.ctrl.fpu.ren3
        XSPerfAccumulate(s"critical_wakeup_from_fma_${j}", Mux(isFMA, PopCount(criticalWakeup), 0.U))
      }
    }
  }
  val canIssueEntries = PopCount(io.canIssue)
  XSPerfHistogram("can_issue_entries", canIssueEntries, true.B, 0, params.numEntries, 1)
  val isBlocked = PopCount(statusArray.map(s => s.valid && s.blocked))
  XSPerfAccumulate("blocked_entries", isBlocked)
  val isScheduled = PopCount(statusArray.map(s => s.valid && s.scheduled))
  XSPerfAccumulate("scheduled_entries", isScheduled)
  val notSelected = RegNext(PopCount(io.canIssue)) - PopCount(is_issued)
  XSPerfAccumulate("not_selected_entries", notSelected)
  val isReplayed = PopCount(io.deqResp.map(resp => resp.valid && !resp.bits.success))
  XSPerfAccumulate("replayed_entries", isReplayed)
}
