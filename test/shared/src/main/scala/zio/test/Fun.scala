/*
 * Copyright 2019-2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.test

import zio.internal.Executor
import zio.{ Runtime, URIO, ZIO }

/**
 * A `Fun[A, B]` is a referentially transparent version of a potentially
 * effectual function from `A` to `B`. Each invocation of the function will be
 * memoized so the function is guaranteed to return the same value for any
 * given input. The function should not involve asynchronous effects.
 */
private[test] final case class Fun[-A, +B] private (private val f: A => B, private val hash: A => Int)
    extends (A => B) {

  def apply(a: A): B =
    map.getOrElseUpdate(hash(a), (a, f(a)))._2

  override def toString: String = {
    val mappings = map.foldLeft(List.empty[String]) { case (acc, (_, (a, b))) => s"$a -> $b" :: acc }
    mappings.mkString("Fun(", ", ", ")")
  }

  private[this] val map = ConcurrentHashMap.empty[Int, (A, B)]
}

private[test] object Fun {

  /**
   * Constructs a new `Fun` from an effectual function. The function should not
   * involve asynchronous effects.
   */
  def make[R, A, B](f: A => URIO[R, B]): ZIO[R, Nothing, Fun[A, B]] =
    makeHash(f)(_.hashCode)

  /**
   * Constructs a new `Fun` from an effectual function and a hashing function.
   * This is useful when the domain of the function does not implement
   * `hashCode` in a way that is consistent with equality.
   */
  def makeHash[R, A, B](f: A => URIO[R, B])(hash: A => Int): ZIO[R, Nothing, Fun[A, B]] =
    ZIO.runtime[R].map { runtime =>
      val funRuntime = withFunExecutor(runtime)
      Fun(a => funRuntime.unsafeRun(f(a)), hash)
    }

  /**
   * Constructs a new `Fun` from a pure function.
   */
  def fromFunction[A, B](f: A => B): Fun[A, B] =
    Fun(f, _.hashCode)

  /**
   * Constructs a new runtime on Scala.js with an unyielding executor so that
   * synchronous effects with a large number of operations can be safely
   * executed.
   */
  private def withFunExecutor[R](runtime: Runtime[R]): Runtime[R] =
    if (TestPlatform.isJS) {
      runtime.withExecutor {
        val ec = runtime.platform.executor.asEC
        Executor.fromExecutionContext(Int.MaxValue)(ec)
      }
    } else runtime
}
