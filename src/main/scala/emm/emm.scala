package emm

import scalaz.{Applicative, Bind, Functor, Monad, Traverse}

import scala.annotation.implicitNotFound

sealed trait Effects {
  type Point[A]
}

sealed trait |:[F[_], T <: Effects] extends Effects {
  type Point[A] = F[T#Point[A]]
}

sealed trait Base extends Effects {
  type Point[A] = A
}

object Effects {

  @implicitNotFound("could not compute a method for mapping over effect stack ${C}; either a member of the stack lacks an Applicative, or its Applicative instance is ambiguous")
  sealed trait Mapper[C <: Effects] {

    def point[A](a: A): C#Point[A]

    def map[A, B](fa: C#Point[A])(f: A => B): C#Point[B]

    def ap[A, B](fa: C#Point[A])(f: C#Point[A => B]): C#Point[B]
  }

  object Mapper {

    implicit def head[F[_]](implicit F: Applicative[F]): Mapper[F |: Base] = new Mapper[F |: Base] {

      def point[A](a: A) = F.point(a)

      def map[A, B](fa: F[A])(f: A => B): F[B] = F.map(fa)(f)

      def ap[A, B](fa: F[A])(f: F[A => B]): F[B] = F.ap(fa)(f)
    }

    implicit def corecurse[F[_], C <: Effects](implicit P: Mapper[C], F: Applicative[F]): Mapper[F |: C] = new Mapper[F |: C] {

      def point[A](a: A) = F.point(P.point(a))

      def map[A, B](fa: F[C#Point[A]])(f: A => B): F[C#Point[B]] =
        F.map(fa) { ca => P.map(ca)(f) }

      def ap[A, B](fa: F[C#Point[A]])(f: F[C#Point[A => B]]): F[C#Point[B]] = {
        val f2 = F.map(f) { cf =>
          { ca: C#Point[A] => P.ap(ca)(cf) }
        }

        F.ap(fa)(f2)
      }
    }
  }

  sealed trait Traverser[C <: Effects] {
    def traverse[G[_]: Applicative, A, B](ca: C#Point[A])(f: A => G[B]): G[C#Point[B]]
  }

  object Traverser {

    implicit def head[F[_]](implicit F: Traverse[F]): Traverser[F |: Base] = new Traverser[F |: Base] {
      def traverse[G[_]: Applicative, A, B](fa: F[A])(f: A => G[B]): G[F[B]] = F.traverse(fa)(f)
    }

    implicit def corecurse[F[_], C <: Effects](implicit C: Traverser[C], F: Traverse[F]): Traverser[F |: C] = new Traverser[F |: C] {

      def traverse[G[_]: Applicative, A, B](fca: F[C#Point[A]])(f: A => G[B]): G[F[C#Point[B]]] = {
        F.traverse(fca) { ca =>
          C.traverse(ca)(f)
        }
      }
    }
  }

  @implicitNotFound("could not prove ${C} is a valid monadic stack; perhaps an effect is lacking a Bind, or a non-outer effect is lacking a Traverse")
  sealed trait Joiner[C <: Effects] {
    def join[A](cca: C#Point[C#Point[A]]): C#Point[A]
  }

  object Joiner {

    implicit def head[F[_]](implicit F: Bind[F]): Joiner[F |: Base] = new Joiner[F |: Base] {
      def join[A](ffa: F[F[A]]) = F.join(ffa)
    }

    implicit def corecurse[F[_], C <: Effects](implicit C: Joiner[C], T: Traverser[C], F: Applicative[F], B: Bind[F]): Joiner[F |: C] = new Joiner[F |: C] {

      def join[A](fcfa: F[C#Point[F[C#Point[A]]]]): F[C#Point[A]] = {
        val ffca = F.map(fcfa) { cfa =>
          F.map(T.traverse(cfa) { fa => fa }) { cca =>
            C.join(cca)
          }
        }

        B.join(ffca)
      }
    }
  }

  sealed trait Expander[F[_], C <: Effects, Out <: Effects] {

    def apply[A](fa: C#Point[A]): Out#Point[F[A]]
  }

  object Expander {

    implicit def head[F[_]]: Expander[F, F |: Base, Base] = new Expander[F, F |: Base, Base] {
      def apply[A](fa: F[A]): F[A] = fa
    }

    implicit def corecurse[F[_], G[_], C <: Effects, C2 <: Effects](implicit C: Expander[F, C, C2]): Expander[F, G |: C, G |: C2] = new Expander[F, G |: C, G |: C2] {

      def apply[A](gca: G[C#Point[A]]): G[C2#Point[F[A]]] =
        gca.asInstanceOf[G[C2#Point[F[A]]]]     // already proven equivalent; evaluation requires a Functor
    }
  }

  sealed trait Collapser[F[_], C <: Effects, Out <: Effects] {

    def apply[A](fa: C#Point[F[A]]): Out#Point[A]
  }

  object Collapser {

    implicit def head[F[_]]: Collapser[F, Base, F |: Base] = new Collapser[F, Base, F |: Base] {

      def apply[A](fa: F[A]): F[A] = fa
    }

    implicit def corecurse[F[_], G[_], C <: Effects, C2 <: Effects](implicit C: Collapser[F, C, C2]): Collapser[F, G |: C, G |: C2] = new Collapser[F, G |: C, G |: C2] {

      def apply[A](gca: G[C#Point[F[A]]]): G[C2#Point[A]] =
        gca.asInstanceOf[G[C2#Point[A]]]      // already proven equivalent; evaluation requires a Functor
    }
  }

  @implicitNotFound("could not lift effect ${F} into stack ${C}; either ${C} does not contain ${F}, or there is no Functor for ${F}")
  sealed trait Lifter[F[_], C <: Effects] {
    def apply[A](fa: F[A]): C#Point[A]
  }

  object Lifter {

    implicit def exacthead[F[_]]: Lifter[F, F |: Base] = new Lifter[F, F |: Base] {
      def apply[A](fa: F[A]): F[A] = fa
    }

    implicit def head[F[_], C <: Effects](implicit P: Mapper[C], F: Functor[F]): Lifter[F, F |: C] = new Lifter[F, F |: C] {
      def apply[A](fa: F[A]): F[C#Point[A]] = F.map(fa) { a => P.point(a) }
    }

    implicit def corecurse[F[_], G[_], C <: Effects](implicit L: Lifter[F, C], G: Applicative[G]): Lifter[F, G |: C] = new Lifter[F, G |: C] {
      def apply[A](fa: F[A]): G[C#Point[A]] = G.point(L(fa))
    }
  }

  @implicitNotFound("could not infer effect stack ${C} from type ${E}; either ${C} does not match ${E}, or you have simply run afoul of SI-2712")
  sealed trait Wrapper[E, C <: Effects] {
    type A

    def apply(e: E): C#Point[A]
  }

  trait WrapperLowPriorityImplicits {

    implicit def head[A0]: Wrapper.Aux[A0, Base, A0] = new Wrapper[A0, Base] {
      type A = A0

      def apply(a: A) = a
    }
  }

  object Wrapper extends WrapperLowPriorityImplicits {
    type Aux[E, C <: Effects, A0] = Wrapper[E, C] { type A = A0 }

    implicit def corecurse[F[_], E, C <: Effects, A0](implicit W: Wrapper.Aux[E, C, A0]): Wrapper.Aux[F[E], F |: C, A0] = new Wrapper[F[E], F |: C] {
      type A = A0

      def apply(fe: F[E]): F[C#Point[A]] =
        fe.asInstanceOf[F[C#Point[A]]]      // already proven equivalent; actual evaluation requires a Functor
    }
  }
}


final case class Emm[C <: Effects, A](run: C#Point[A]) {

  def map[B](f: A => B)(implicit C: Effects.Mapper[C]): Emm[C, B] = Emm(C.map(run)(f))

  def flatMap[B](f: A => Emm[C, B])(implicit A: Effects.Mapper[C], B: Effects.Joiner[C]): Emm[C, B] =
    Emm(B.join(A.map(run) { a => f(a).run }))

  def flatMapM[G[_], B](f: A => G[B])(implicit L: Effects.Lifter[G, C], A: Effects.Mapper[C], B: Effects.Joiner[C]): Emm[C, B] =
    flatMap { a => Emm(L(f(a))) }

  def expand[G[_], C2 <: Effects](implicit C: Effects.Expander[G, C, C2]): Emm[C2, G[A]] =
    Emm(C(run))

  def collapse[G[_], B, C2 <: Effects](implicit ev: A =:= G[B], C: Effects.Collapser[G, C, C2]): Emm[C2, B] =
    Emm(C(run.asInstanceOf[C#Point[G[B]]]))     // cast is just to avoid unnecessary mapping
}

trait EmmLowPriorityImplicits1 {
  import Effects._

  implicit def applicativeInstance[C <: Effects](implicit C: Mapper[C]): Applicative[({ type λ[α] = Emm[C, α] })#λ] = new Applicative[({ type λ[α] = Emm[C, α] })#λ] {

    def point[A](a: => A): Emm[C, A] = new Emm(C.point(a))

    def ap[A, B](fa: => Emm[C, A])(f: => Emm[C, A => B]): Emm[C, B] = new Emm(C.ap(fa.run)(f.run))
  }
}

trait EmmLowPriorityImplicits2 extends EmmLowPriorityImplicits1 {
  import Effects._

  implicit def monadInstance[C <: Effects : Mapper : Joiner]: Monad[({ type λ[α] = Emm[C, α] })#λ] = new Monad[({ type λ[α] = Emm[C, α] })#λ] {

    def point[A](a: => A): Emm[C, A] = new Emm(implicitly[Mapper[C]].point(a))

    def bind[A, B](fa: Emm[C, A])(f: A => Emm[C, B]): Emm[C, B] = fa flatMap f
  }
}

object Emm extends EmmLowPriorityImplicits2 {
  import Effects._

  implicit def traverseInstance[C <: Effects](implicit C: Traverser[C]): Traverse[({ type λ[α] = Emm[C, α] })#λ] = new Traverse[({ type λ[α] = Emm[C, α] })#λ] {
    def traverseImpl[G[_]: Applicative, A, B](fa: Emm[C, A])(f: A => G[B]): G[Emm[C, B]] =
      Applicative[G].map(C.traverse(fa.run)(f)) { new Emm(_) }
  }
}