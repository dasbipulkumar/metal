package net.alasc.ptrcoll

trait Nextable extends Pointable {
  /** Returns a pointer to the next element if available,
    * or returns a null pointer.
    */
  def ptrNext(ptr: ValidPtr): Ptr
}
