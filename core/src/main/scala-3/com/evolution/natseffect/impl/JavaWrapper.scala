package com.evolution.natseffect.impl

import scala.compiletime.asMatchable

private[natseffect] trait JavaWrapper[+T] {

  def asJava: T

  override def hashCode(): Int = asJava.hashCode()

  override def equals(obj: Any): Boolean = obj.asMatchable match {
    case that: JavaWrapper[?] => this.asJava.asInstanceOf[Any] == that.asJava
    case _                    => false
  }

  override def toString: String = asJava.toString
}
