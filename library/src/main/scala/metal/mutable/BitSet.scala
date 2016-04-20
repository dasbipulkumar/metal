package metal
package mutable

import spire.syntax.cfor._

abstract class BitSet extends generic.BitSet with mutable.SortedSet[Int] {

  def words: Array[Long]

  def reset(): Unit = {
    cforRange(0 until nWords) { w =>
      words(w) = 0L
    }
  }

  def toImmutable: Immutable = new immutable.BitSet(words.clone) // TODO: trim array

  def toScala = toImmutable.toScala

  def ptrRemoveAndAdvance(ptr: VPtr[this.type]): Ptr[this.type] = {
    val nextPtr = ptrNext(ptr)
    ptrRemove(ptr)
    nextPtr
  }

}

/** Bitset represented by an array of longs, each `Long` containing the information
  * about the membership of 64 integers. The elements are all non-negative.
  * 
  * The part of words(i >= nWords) must always be 0L.
  */
final class ResizableBitSet(var words: Array[Long], var nWords: Int) extends mutable.BitSet {

  import generic.BitSet.LogWL

  def clear(): Unit = {
    words = new Array[Long](mutable.BitSet.startSize)
    nWords = 0
  }

  def result(): Immutable = {
    val res = new immutable.BitSet(words)
    words = new Array[Long](0)
    nWords = 0
    res
  }

  def ptrAddKey[@specialized L](keyL: L): VPtr[this.type] = {
    val key = keyL.asInstanceOf[Int]
    val w = key >>> LogWL
    if (w >= words.length) {
      val newWords = new Array[Long](util.nextPowerOfTwo(w + 1))
      java.lang.System.arraycopy(words, 0, newWords, 0, nWords)
      words = newWords
    }
    words(w) |= (1L << key)
    nWords = scala.math.max(nWords, w + 1)
    VPtr(this, key)
  }

  def ptrRemove(ptr: VPtr[this.type]): Unit = {
    val i = ptr.raw.toInt
    val w = i >>> LogWL
    if (w >= nWords) return
    words(w) &= ~(1L << i)
  }

}

/** Bitset represented by an array of longs, each `Long` containing the information
  * about the membership of 64 integers. The elements are all non-negative.
  * 
  * The size of this BitSet is set at creation time and is never modified.
  */
final class FixedBitSet(var words: Array[Long]) extends mutable.BitSet {

  import generic.BitSet.LogWL

  def nWords = words.length

  /** Equivalent to [[reset]], because deallocating does not make sense. */
  def clear(): Unit = reset()

  def result(): Immutable = {
    val res = new immutable.BitSet(words)
    words = new Array[Long](words.length)
    res
  }

  def ptrAddKey[@specialized L](keyL: L): VPtr[this.type] = {
    val key = keyL.asInstanceOf[Int]
    val w = key >>> LogWL
    words(w) |= (1L << key)
    VPtr(this, key)
  }

  def ptrRemove(ptr: VPtr[this.type]): Unit = {
    val i = ptr.raw.toInt
    val w = i >>> LogWL
    words(w) &= ~(1L << i)
  }

}

object BitSet extends mutable.SetBuilder[Int, mutable.BitSet] {

  import generic.BitSet.WordLength

  @inline final def startSize = 2

  def nWordsForSize(n: Int) =
    if (n == 0) 0 else ((n - 1) / WordLength) + 1

  def ofAllocatedWordSize(nWords: Int): mutable.BitSet =
    new mutable.ResizableBitSet(new Array[Long](nWords), 0)

  def reservedSize(n: Long): mutable.BitSet = {
    require(n.isValidInt)
    ofAllocatedWordSize(spire.math.max(startSize, nWordsForSize(n.toInt)))
  }

  def fixedSize(n: Long): mutable.BitSet = {
    require(n.isValidInt)
    new mutable.FixedBitSet(new Array[Long](nWordsForSize(n.toInt)))
  }

  override def fromIterable(items: Iterable[Int]): mutable.BitSet = items match {
    case bs1: scala.collection.immutable.BitSet.BitSet1 => new mutable.ResizableBitSet(Array(bs1.elems), 1)
    case bsn: scala.collection.immutable.BitSet.BitSetN => new mutable.ResizableBitSet(bsn.elems, bsn.elems.length)
    case bs: scala.collection.BitSet =>
      val bm = bs.toBitMask
      new mutable.ResizableBitSet(bm, bm.length)
    case _ => super.fromIterable(items)
  }

}
