/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Fuseable;
import reactor.core.Receiver;

/**
 * Uses a resource, generated by a supplier for each individual Subscriber,
 * while streaming the values from a
 * Publisher derived from the same resource and makes sure the resource is released
 * if the sequence terminates or the Subscriber cancels.
 * <p>
 * <p>
 * Eager resource cleanup happens just before the source termination and exceptions
 * raised by the cleanup Consumer may override the terminal even. Non-eager
 * cleanup will drop any exception.
 *
 * @param <T> the value type streamed
 * @param <S> the resource type
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxUsing<T, S> extends Flux<T> implements Receiver, Fuseable {

	final Callable<S> resourceSupplier;

	final Function<? super S, ? extends Publisher<? extends T>> sourceFactory;

	final Consumer<? super S> resourceCleanup;

	final boolean eager;

	public FluxUsing(Callable<S> resourceSupplier,
			Function<? super S, ? extends Publisher<? extends T>> sourceFactory,
			Consumer<? super S> resourceCleanup,
			boolean eager) {
		this.resourceSupplier = Objects.requireNonNull(resourceSupplier, "resourceSupplier");
		this.sourceFactory = Objects.requireNonNull(sourceFactory, "sourceFactory");
		this.resourceCleanup = Objects.requireNonNull(resourceCleanup, "resourceCleanup");
		this.eager = eager;
	}

	@Override
	public Object upstream() {
		return resourceSupplier;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		S resource;

		try {
			resource = resourceSupplier.call();
		} catch (Throwable e) {
			Operators.error(s, Operators.onOperatorError(e));
			return;
		}

		Publisher<? extends T> p;

		try {
			p = sourceFactory.apply(resource);
		} catch (Throwable e) {

			try {
				resourceCleanup.accept(resource);
			} catch (Throwable ex) {
				ex.addSuppressed(Operators.onOperatorError(ex));
				e = ex;
			}

			Operators.error(s, Operators.onOperatorError(e));
			return;
		}

		if (p == null) {
			Throwable e = new NullPointerException("The sourceFactory returned a null value");
			try {
				resourceCleanup.accept(resource);
			} catch (Throwable ex) {
				Throwable _ex = Operators.onOperatorError(ex);
				_ex.addSuppressed(e);
				e = _ex;
			}

			Operators.error(s, Operators.onOperatorError(e));
			return;
		}

		if (p instanceof Fuseable) {
			p.subscribe(new UsingFuseableSubscriber<>(s, resourceCleanup, resource, eager));
		}
		else if (s instanceof ConditionalSubscriber) {
			p.subscribe(new UsingConditionalSubscriber<>((ConditionalSubscriber<? super T>)s, resourceCleanup, resource, eager));
		}
		else {
			p.subscribe(new UsingSubscriber<>(s, resourceCleanup, resource, eager));
		}
	}

	static final class UsingSubscriber<T, S>
			implements Subscriber<T>, QueueSubscription<T> {

		final Subscriber<? super T> actual;

		final Consumer<? super S> resourceCleanup;

		final S resource;

		final boolean eager;

		Subscription s;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<UsingSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(UsingSubscriber.class, "wip");

		public UsingSubscriber(Subscriber<? super T> actual, Consumer<? super S> resourceCleanup, S
				resource, boolean eager) {
			this.actual = actual;
			this.resourceCleanup = resourceCleanup;
			this.resource = resource;
			this.eager = eager;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			if (WIP.compareAndSet(this, 0, 1)) {
				s.cancel();

				cleanup();
			}
		}

		void cleanup() {
			try {
				resourceCleanup.accept(resource);
			} catch (Throwable e) {
				Operators.onErrorDropped(e);
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				} catch (Throwable e) {
					Throwable _e = Operators.onOperatorError(e);
					_e.addSuppressed(t);
					t = _e;
				}
			}

			actual.onError(t);

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public void onComplete() {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				} catch (Throwable e) {
					actual.onError(Operators.onOperatorError(e));
					return;
				}
			}

			actual.onComplete();

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public int requestFusion(int requestedMode) {
			return NONE; // always reject, upstream turned out to be non-fuseable after all
		}

		@Override
		public void clear() {
			// ignoring fusion methods
		}

		@Override
		public boolean isEmpty() {
			// ignoring fusion methods
			return wip != 0;
		}

		@Override
		public T poll() {
			return null;
		}

		@Override
		public int size() {
			return 0;
		}
	}

	static final class UsingFuseableSubscriber<T, S>
			implements Subscriber<T>, QueueSubscription<T> {

		final Subscriber<? super T> actual;

		final Consumer<? super S> resourceCleanup;

		final S resource;

		final boolean eager;

		QueueSubscription<T> s;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<UsingFuseableSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(UsingFuseableSubscriber.class, "wip");

		int mode;

		public UsingFuseableSubscriber(Subscriber<? super T> actual, Consumer<? super S> resourceCleanup, S
				resource, boolean eager) {
			this.actual = actual;
			this.resourceCleanup = resourceCleanup;
			this.resource = resource;
			this.eager = eager;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			if (WIP.compareAndSet(this, 0, 1)) {
				s.cancel();

				cleanup();
			}
		}

		void cleanup() {
			try {
				resourceCleanup.accept(resource);
			} catch (Throwable e) {
				Operators.onErrorDropped(e);
			}
		}

		@SuppressWarnings("unchecked")
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = (QueueSubscription<T>)s;

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				} catch (Throwable e) {
					Throwable _e = Operators.onOperatorError(e);
					_e.addSuppressed(t);
					t = _e;
				}
			}

			actual.onError(t);

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public void onComplete() {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				} catch (Throwable e) {
					actual.onError(Operators.onOperatorError(e));
					return;
				}
			}

			actual.onComplete();

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public void clear() {
			s.clear();
		}

		@Override
		public boolean isEmpty() {
			return s.isEmpty();
		}

		@Override
		public T poll() {
			T v = s.poll();

			if (v == null && mode == SYNC) {
				if (WIP.compareAndSet(this, 0, 1)) {
					resourceCleanup.accept(resource);
				}
			}
			return v;
		}

		@Override
		public int requestFusion(int requestedMode) {
			int m = s.requestFusion(requestedMode);
			mode = m;
			return m;
		}

		@Override
		public int size() {
			return s.size();
		}
	}

	static final class UsingConditionalSubscriber<T, S>
			implements ConditionalSubscriber<T>, QueueSubscription<T> {

		final ConditionalSubscriber<? super T> actual;

		final Consumer<? super S> resourceCleanup;

		final S resource;

		final boolean eager;

		Subscription s;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<UsingConditionalSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(UsingConditionalSubscriber.class, "wip");

		public UsingConditionalSubscriber(ConditionalSubscriber<? super T> actual, Consumer<? super S> resourceCleanup, S
				resource, boolean eager) {
			this.actual = actual;
			this.resourceCleanup = resourceCleanup;
			this.resource = resource;
			this.eager = eager;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			if (WIP.compareAndSet(this, 0, 1)) {
				s.cancel();

				cleanup();
			}
		}

		void cleanup() {
			try {
				resourceCleanup.accept(resource);
			} catch (Throwable e) {
				Operators.onErrorDropped(e);
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}

		@Override
		public boolean tryOnNext(T t) {
			return actual.tryOnNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				} catch (Throwable e) {
					Throwable _e = Operators.onOperatorError(e);
					_e.addSuppressed(t);
					t = _e;
				}
			}

			actual.onError(t);

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public void onComplete() {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				} catch (Throwable e) {
					actual.onError(Operators.onOperatorError(e));
					return;
				}
			}

			actual.onComplete();

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public int requestFusion(int requestedMode) {
			return NONE; // always reject, upstream turned out to be non-fuseable after all
		}

		@Override
		public void clear() {
			// ignoring fusion methods
		}

		@Override
		public boolean isEmpty() {
			// ignoring fusion methods
			return wip != 0;
		}

		@Override
		public T poll() {
			return null;
		}

		@Override
		public int size() {
			return 0;
		}
	}
}
