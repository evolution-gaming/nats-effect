package com.evolution.natseffect.impl

private[natseffect] trait JavaWrapper[+T] {

  def asJava: T

  override def hashCode(): Int = asJava.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case that: JavaWrapper[?] => this.asJava.asInstanceOf[Any] == that.asJava
    case _                    => false
  }

  override def toString: String = asJava.toString
}
