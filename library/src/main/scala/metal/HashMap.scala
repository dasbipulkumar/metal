package metal

import scala.annotation.{switch, tailrec}
import scala.reflect.ClassTag

import spire.algebra.Order
import spire.syntax.cfor._
import spire.util.Opt

class HashMap[K, V](
  /** Slots for keys. */
  var keys: Array[K],
  /** Slots for values. */
  var vals: Array[V],
  /** Status of the slots in the hash table.
    * 
    * 0 = unused
    * 2 = once used, now empty but not yet overwritten
    * 3 = used
    */ 
  var buckets: Array[Byte],
  /** Number of defined slots. */
  var len: Int,
  /** Number of used slots (used >= len). */
  var used: Int,
  // hashing internals
  /** size - 1, used for hashing. */
  var mask: Int,
  /** Point at which we should grow. */
  var limit: Int
)(implicit val ctK: ClassTag[K], val ctV: ClassTag[V]) extends MMap[K, V] { self =>

  @inline final def size: Int = len

  @inline final override def isEmpty: Boolean = len == 0

  @inline final override def nonEmpty: Boolean = len > 0

  def copy: HashMap[K, V] = new HashMap[K, V](
    keys = keys.clone,
    vals = vals.clone,
    buckets = buckets.clone,
    len = len,
    used = used,
    mask = mask,
    limit = limit)

  final def ptrAddKey[@specialized L](key: L): VPtr[Tag] = {
    val keysL = keys.asInstanceOf[Array[L]]
    @inline @tailrec def loop(i: Int, perturbation: Int): VPtr[Tag] = {
      val j = i & mask
      val status = buckets(j)
      if (status == 0) {
        keysL(j) = key
        buckets(j) = 3
        len += 1
        used += 1
        if (used > limit) {
          grow()
          val VPtr(vp) = ptrFind[L](key)
          vp
        } else VPtr[Tag](j)
      } else if (status == 2 && ptrFind[L](key).isNull) {
        keysL(j) = key
        buckets(j) = 3
        len += 1
        VPtr[Tag](j)
      } else if (keysL(j) == key) {
        VPtr[Tag](j)
      } else {
        loop((i << 2) + i + perturbation + 1, perturbation >> 5)
      }
    }
    val i = key.## & 0x7fffffff
    loop(i, i)
  }

  final def ptrUpdate[@specialized W](ptr: VPtr[Tag], v: W): Unit = {
    vals.asInstanceOf[Array[W]](ptr.v.toInt) = v
  }


  final def ptrRemoveAndAdvance(ptr: VPtr[Tag]): Ptr[Tag] = {
    val next = ptrNext(ptr)
    ptrRemove(ptr)
    next
  }

  final def ptrRemove(ptr: VPtr[Tag]): Unit = {
    val j = ptr.v.toInt
    buckets(j) = 2
    vals(j) = null.asInstanceOf[V]
    len -= 1
  }

  final def ptrFind[@specialized L](key: L): Ptr[Tag] = {
    val keysL = keys.asInstanceOf[Array[L]]
    @inline @tailrec def loop(i: Int, perturbation: Int): Ptr[Tag] = {
      val j = i & mask
      val status = buckets(j)
      if (status == 0) Ptr.Null[Tag]
      else if (status == 3 && keysL(j) == key) VPtr[Tag](j)
      else loop((i << 2) + i + perturbation + 1, perturbation >> 5)
    }
    val i = key.## & 0x7fffffff
    loop(i, i)
  }

  /** Absorbs the given map's contents into this map.
    * 
    * This method does not copy the other map's contents. Thus, this
    * should only be used when there are no saved references to the
    * other map. It is private, and exists primarily to simplify the
    * implementation of certain methods.
    * 
    * This is an O(1) operation, although it can potentially generate a
    * lot of garbage (if the map was previously large).
    */
  private[this] def absorb(rhs: HashMap[K, V]): Unit = {
    keys = rhs.keys
    vals = rhs.vals
    buckets = rhs.buckets
    len = rhs.len
    used = rhs.used
    mask = rhs.mask
    limit = rhs.limit
  }

  /**
    * Grow the underlying array to best accomodate the map's size.
    * 
    * To preserve hashing access speed, the map's size should never be
    * more than 66% of the underlying array's size. When this size is
    * reached, the map needs to be updated (using this method) to have a
    * larger array.
    * 
    * The underlying array's size must always be a multiple of 2, which
    * means this method grows the array's size by 2x (or 4x if the map
    * is very small). This doubling helps amortize the cost of
    * resizing, since as the map gets larger growth will happen less
    * frequently. This method returns a null of type Unit1[A] to
    * trigger specialization without allocating an actual instance.
    * 
    * Growing is an O(n) operation, where n is the map's size.
   */
  final def grow(): Unit = {
    val next = keys.length * (if (keys.length < 10000) 4 else 2)
    val map = HashMap.ofSize[K, V](next)
    cfor(0)(_ < buckets.length, _ + 1) { i =>
      if (buckets(i) == 3) {
        val vp = map.ptrAddKeyFromArray(keys, i)
        map.ptrUpdateFromArray(vp, vals, i)
      }
    }
    absorb(map)
  }

  final def ptrNull: Ptr[Tag] = Ptr.Null[Tag]

  final def ptrStart: Ptr[Tag] = {
    var i = 0
    while (i < buckets.length && buckets(i) != 3) i += 1
    if (i < buckets.length) VPtr[Tag](i) else Ptr.Null[Tag]
  }

  final def ptrNext(ptr: VPtr[Tag]): Ptr[Tag] = {
    var i = ptr.v.toInt + 1
    while (i < buckets.length && buckets(i) != 3) i += 1
    if (i < buckets.length) VPtr[Tag](i) else Ptr.Null[Tag]
  }

  final def ptrKey[@specialized L](ptr: VPtr[Tag]): L = keys.asInstanceOf[Array[L]](ptr.v.toInt)

  final def ptrValue[@specialized W](ptr: VPtr[Tag]): W = vals.asInstanceOf[Array[W]](ptr.v.toInt)

}

object HashMap extends MMapFactory[Any, Dummy, Any] {

  def empty[K, V](implicit ctK: ClassTag[K], d: Dummy[K], e: KLBEv[K], ctV: ClassTag[V]): HashMap[K, V] = ofSize(0)(ctK, d, e, ctV)

  /*`* Creates a HashMap that can hold n unique keys without resizing itself.
    *
    * Note that the internal representation will allocate more space
    * than requested to satisfy the requirements of internal
    * alignment. Map uses arrays whose lengths are powers of two, and
    * needs at least 33% of the map free to enable good hashing
    * performance.
    * 
    * Example: HashMap.ofSize[Int, String](100).
    */

  def ofSize[K:ClassTag:Dummy:KLBEv, V:ClassTag](n: Int): HashMap[K, V] = ofAllocatedSize(n / 2 * 3)

  /** Allocates an empty HashMap, with underlying storage of size n.
    * 
    * This method is useful if you know exactly how big you want the
    * underlying array to be. In most cases ofSize() is probably what
    * you want instead.
    */
  private[metal] def ofAllocatedSize[K:ClassTag, V:ClassTag](n: Int) = {
    val sz = Util.nextPowerOfTwo(n) match {
      case n if n < 0 => sys.error(s"Bad allocated size $n for collection")
      case 0 => 8
      case n => n
    }
    new HashMap[K, V](
      keys = new Array[K](sz),
      vals = new Array[V](sz),
      buckets = new Array[Byte](sz),
      len = 0,
      used = 0,
      mask = sz - 1,
      limit = (sz * 0.65).toInt)
  }

}