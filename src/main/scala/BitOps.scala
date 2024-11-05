package org.lichess.compression

object BitOps:

  def writeSigned(values: Array[Int], writer: BitOps.Writer): Unit =
    values.foreach(n => writeSigned(n, writer))

  def writeSigned(n: Int, writer: BitOps.Writer): Unit =
    // zigzag encode
    writeUnsigned((n << 1) ^ (n >> 31), writer)

  def writeUnsigned(n: Int, writer: BitOps.Writer): Unit =
    if (n & ~0x1f) == 0 then writer.writeBits(n, 6)
    else
      writer.writeBits(n | 0x20, 6)
      var remaining = n >>> 5
      while (remaining & ~0x07) != 0 do
        writer.writeBits(remaining | 0x08, 4)
        remaining >>>= 3
      // While loop terminated, so 4th bit is 0
      writer.writeBits(remaining, 4)

  def readUnsigned(reader: BitOps.Reader): Int =
    var n = reader.readBits(6)
    if n > 0x1f then
      n &= 0x1f
      var curShift = 5
      var curVal   = 0
      while
        curVal = reader.readBits(4)
        curVal > 0x07
      do
        n |= (curVal & 0x07) << curShift
        curShift += 3
      n |= curVal << curShift
    n

  def readSigned(reader: BitOps.Reader): Int =
    val n = readUnsigned(reader)
    (n >>> 1) ^ -(n & 1) // zigzag decode

  def readSigned(reader: BitOps.Reader, numMoves: Int): Array[Int] =
    Array.tabulate(numMoves) { _ =>
      val n = readUnsigned(reader)
      (n >>> 1) ^ -(n & 1) // zigzag decode
    }

  class Reader(bytes: Array[Byte]):
    private val BitMasks         = BitOps.getBitMasks
    private val bb               = java.nio.ByteBuffer.wrap(bytes)
    private var numRemainingBits = 0
    private var pendingBits      = 0

    private def readNext(): Unit =
      if bb.remaining >= 4 then
        pendingBits = bb.getInt
        numRemainingBits = 32
      else
        numRemainingBits = bb.remaining * 8
        pendingBits = (bb.get & 0xff) << (numRemainingBits - 8)
        var s = numRemainingBits - 16
        while s >= 0 do
          pendingBits |= (bb.get & 0xff) << s
          s -= 8

    def readBits(numReqBits: Int): Int =
      if numRemainingBits >= numReqBits then
        numRemainingBits -= numReqBits
        (pendingBits >>> numRemainingBits) & BitMasks(numReqBits)
      else
        val res        = pendingBits & BitMasks(numRemainingBits)
        val neededBits = numReqBits - numRemainingBits
        readNext()
        (res << neededBits) | readBits(neededBits)

  class Writer:
    private val BitMasks         = BitOps.getBitMasks
    private val buffer           = collection.mutable.ArrayBuffer[Int]()
    private var numRemainingBits = 32
    private var pendingBits      = 0

    def writeBits(data: Int, numBits: Int): Unit =
      val maskedData = data & BitMasks(numBits)
      numRemainingBits -= numBits
      if numRemainingBits >= 0 then pendingBits |= maskedData << numRemainingBits
      else
        buffer.append(pendingBits | (maskedData >>> -numRemainingBits))
        numRemainingBits += 32
        pendingBits = maskedData << numRemainingBits

    def toArray(): Array[Byte] =
      val numPendingBytes = (39 - numRemainingBits) >> 3
      val bb              = java.nio.ByteBuffer.allocate(4 * buffer.size + numPendingBytes)
      buffer.foreach(bb.putInt)
      if numPendingBytes == 4 then bb.putInt(pendingBits)
      else for i <- 0 until numPendingBytes do bb.put((pendingBits >>> (24 - i * 8)).toByte)
      bb.array()

  private def getBitMasks: Array[Int] =
    Array.tabulate(32)(i => (1 << i) - 1)

end BitOps