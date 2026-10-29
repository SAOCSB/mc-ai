package io.github.saocsb.mcai.core.script;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Symbol;
import org.mozilla.javascript.SymbolKey;
import org.mozilla.javascript.Undefined;

/**
 * Checks in front of built-ins that can loop or allocate a lot in one native call, where the
 * instruction observer never runs: every {@code Array.prototype} and {@code String.prototype} method,
 * array and string iteration (spread), {@code Array.from}, {@code RegExp} exec/test and {@code JSON}.
 * Without them {@code new Array(1e9).fill(0)} would run for minutes and exhaust the heap.
 *
 * <p>Arrays passed to these built-ins may have at most {@link #MAX_ARRAY_LENGTH} elements and strings
 * at most {@link #MAX_STRING_LENGTH} characters (results of repeat/padStart/padEnd/replace/join
 * included); violations are limit errors. {@code Array.prototype} methods only accept real arrays,
 * so a {@code length} getter cannot report one length to the check and another to the native loop:
 * {@code arguments} objects are copied to an array first (mutating methods reject them), and other
 * array-likes go through {@code Array.from}, which copies them too. Array iterators accept any
 * array-like, because they read the length again (running any getter) on every step. Every guarded
 * call also counts toward the periodic deadline check, and JSON.stringify gets a replacer that does
 * the same for every value it visits.
 */
final class NativeGuards {
	static final long MAX_ARRAY_LENGTH = 1L << 21;
	static final long MAX_STRING_LENGTH = 1L << 22;
	private static final int MAX_FLAT_DEPTH = 64;
	private static final java.util.Set<String> MUTATING = java.util.Set.of("copyWithin", "fill", "pop", "push",
			"reverse", "shift", "sort", "splice", "unshift");
	private static final java.util.Set<String> ITERATORS = java.util.Set.of("values", "keys", "entries");

	/** Validates a call; may substitute the {@code this} value passed on to the built-in. */
	@FunctionalInterface
	private interface Check {
		Scriptable check(Scriptable thisObj, Object[] args);
	}

	/** A check that keeps {@code this}. */
	@FunctionalInterface
	private interface Validation {
		void validate(Scriptable thisObj, Object[] args);
	}

	private static Check keep(Validation v) {
		return (t, a) -> {
			v.validate(t, a);
			return t;
		};
	}

	private final ScriptSession session;
	private final ScriptableObject scope;

	private NativeGuards(ScriptSession session, ScriptableObject scope) {
		this.session = session;
		this.scope = scope;
	}

	static void install(ScriptableObject scope, ScriptSession session) {
		new NativeGuards(session, scope).installAll();
	}

	private void installAll() {
		ScriptableObject arrays = (ScriptableObject) ScriptableObject.getArrayPrototype(scope);
		for (Object id : arrays.getAllIds()) {
			if (!(id instanceof String name) || name.equals("constructor") || name.equals("length")) {
				continue;
			}
			String label = "Array.prototype." + name + "()";
			Validation validation = switch (name) {
				case "concat" -> (t, a) -> {
					long n = arrayLength(label, t);
					for (Object o : a) {
						n += o instanceof NativeArray other ? other.getLength() : 1;
					}
					checkArray(label, "the result has", n);
				};
				case "flat" -> (t, a) -> {
					arrayLength(label, t);
					double depth = a.length > 0 && !Undefined.isUndefined(a[0]) ? ScriptRuntime.toInteger(a[0]) : 1;
					flatSize(label, (NativeArray) t, (int) Math.max(0, Math.min(depth, MAX_FLAT_DEPTH)), 0, new long[1]);
				};
				case "join", "toString", "toLocaleString" -> (t, a) -> joinSize(label, t,
						name.equals("join") && a.length > 0 && a[0] instanceof CharSequence sep ? sep.length() : 1);
				default -> (t, a) -> arrayLength(label, t);
			};
			Check check;
			if (ITERATORS.contains(name)) {
				check = keep((t, a) -> checkArray(label, "the array has", lengthOf(t)));
			} else {
				check = (t, a) -> {
					Scriptable self = argumentsCopy(label, name, t);
					validation.validate(self, a);
					return self;
				};
			}
			wrap(arrays, name, check);
		}
		wrap(arrays, SymbolKey.ITERATOR, keep((t, a) -> checkArray("array iteration", "the array has", lengthOf(t))));

		if (ScriptableObject.getProperty(scope, "Array") instanceof Scriptable arrayCtor) {
			wrap(arrayCtor, "from", keep(this::arrayFrom));
		}

		ScriptableObject strings = (ScriptableObject) ScriptableObject.getClassPrototype(scope, "String");
		for (Object id : strings.getAllIds()) {
			if (!(id instanceof String name) || name.equals("constructor") || name.equals("length")) {
				continue;
			}
			String label = "String.prototype." + name + "()";
			wrap(strings, name, keep((t, a) -> {
				long len = stringLength(t);
				checkString(label, len);
				for (Object arg : a) {
					if (arg instanceof CharSequence cs) {
						checkString(label, cs.length());
					}
				}
				checkString(label, resultLength(name, len, a));
			}));
		}
		wrap(strings, SymbolKey.ITERATOR, keep((t, a) -> checkString("string iteration", stringLength(t))));

		Scriptable regexps = ScriptableObject.getClassPrototype(scope, "RegExp");
		if (regexps != null) {
			for (String name : new String[] {"exec", "test"}) {
				wrap(regexps, name, keep((t, a) -> stringArgs("RegExp.prototype." + name + "()", a)));
			}
		}
		if (ScriptableObject.getProperty(scope, "JSON") instanceof ScriptableObject json) {
			wrap(json, "parse", keep((t, a) -> stringArgs("JSON.parse()", a)));
			if (ScriptableObject.getProperty(json, "stringify") instanceof Callable original) {
				json.defineProperty("stringify", new LambdaFunction(scope, "stringify", 3, (cx, s, thisObj, args) -> {
					session.tick();
					Object replacer = args.length > 1 ? args[1] : Undefined.instance;
					Object[] forwarded = {args.length > 0 ? args[0] : Undefined.instance,
							replacer instanceof NativeArray ? replacer : tickingReplacer(replacer instanceof Callable c ? c : null),
							args.length > 2 ? args[2] : Undefined.instance};
					return original.call(cx, s, thisObj, forwarded);
				}, false), ScriptableObject.DONTENUM);
			}
		}
	}

	/** JSON.stringify for host code (log), with the same per-value checks; returns null if not serializable. */
	String stringify(Context cx, Object value) {
		Object json = NativeJSON.stringify(cx, scope, value, tickingReplacer(null), Undefined.instance);
		return json instanceof CharSequence cs ? cs.toString() : null;
	}

	static String stringify(ScriptSession session, ScriptableObject scope, Context cx, Object value) {
		return new NativeGuards(session, scope).stringify(cx, value);
	}

	private LambdaFunction tickingReplacer(Callable user) {
		return new LambdaFunction(scope, "replacer", 2, (cx, s, thisObj, args) -> {
			session.tick();
			Object value = args.length > 1 ? args[1] : Undefined.instance;
			if (value instanceof CharSequence cs) {
				checkString("JSON.stringify()", cs.length());
			}
			return user != null ? user.call(cx, s, thisObj, args) : value;
		}, false);
	}

	private void arrayFrom(Scriptable thisObj, Object[] args) {
		Object items = args.length > 0 ? args[0] : Undefined.instance;
		if (items instanceof CharSequence cs) {
			checkString("Array.from()", cs.length());
		} else if (items instanceof NativeArray array) {
			checkArray("Array.from()", "the array has", array.getLength());
		} else if (items instanceof Scriptable s && !ScriptableObject.hasProperty(s, SymbolKey.ITERATOR)) {
			Object length = ScriptableObject.getProperty(s, "length");
			if (length != Scriptable.NOT_FOUND) {
				long n = ScriptRuntime.toLength(length);
				checkArray("Array.from()", "the array-like object has", n);
				// copy it, so that a length getter cannot report a different length to the native code
				Object[] copy = new Object[(int) n];
				for (int i = 0; i < n; i++) {
					session.tick();
					Object e = ScriptableObject.getProperty(s, i);
					copy[i] = e == Scriptable.NOT_FOUND ? Undefined.instance : e;
				}
				args[0] = Context.getCurrentContext().newArray(scope, copy);
			}
		}
	}

	private void wrap(Scriptable owner, Object key, Check check) {
		Object value = key instanceof Symbol sym ? ScriptableObject.getProperty(owner, sym)
				: ScriptableObject.getProperty(owner, (String) key);
		if (!(value instanceof Callable original)) {
			return;
		}
		String name = key instanceof String s ? s : "[Symbol.iterator]";
		int arity = original instanceof BaseFunction f ? f.getLength() : 0;
		LambdaFunction guarded = new LambdaFunction(scope, name, arity, (cx, s, thisObj, args) -> {
			session.tick();
			Scriptable self = check.check(thisObj, args);
			return original.call(cx, s, self, args);
		}, false);
		ScriptableObject target = (ScriptableObject) owner;
		if (key instanceof Symbol sym) {
			target.defineProperty(sym, guarded, ScriptableObject.DONTENUM);
		} else {
			target.defineProperty((String) key, guarded, ScriptableObject.DONTENUM);
		}
	}

	/** An {@code arguments} object copied to a real array (once, so its length cannot change); other values as they are. */
	private Scriptable argumentsCopy(String label, String method, Scriptable thisObj) {
		if (thisObj instanceof NativeArray || thisObj == null || !"Arguments".equals(thisObj.getClassName())) {
			return thisObj;
		}
		if (MUTATING.contains(method)) {
			throw ScriptRuntime.typeError(label + " cannot change an arguments object; copy it with Array.from(arguments) first");
		}
		long n = lengthOf(thisObj);
		checkArray(label, "the arguments object has", n);
		Object[] copy = new Object[(int) n];
		for (int i = 0; i < n; i++) {
			Object e = ScriptableObject.getProperty(thisObj, i);
			copy[i] = e == Scriptable.NOT_FOUND ? Undefined.instance : e;
		}
		return Context.getCurrentContext().newArray(scope, copy);
	}

	private static long lengthOf(Scriptable obj) {
		if (obj instanceof NativeArray array) {
			return array.getLength();
		}
		Object length = obj == null ? null : ScriptableObject.getProperty(obj, "length");
		return length instanceof Number n && n.doubleValue() > 0 ? (long) Math.min(9e18, n.doubleValue()) : 0;
	}

	private long arrayLength(String label, Scriptable thisObj) {
		if (!(thisObj instanceof NativeArray array)) {
			throw ScriptRuntime.typeError(label + " only works on arrays here; convert array-like values with Array.from() first");
		}
		long length = array.getLength();
		checkArray(label, "the array has", length);
		return length;
	}

	private void joinSize(String label, Scriptable thisObj, long separator) {
		long length = arrayLength(label, thisObj);
		NativeArray array = (NativeArray) thisObj;
		long total = separator * Math.max(0, length - 1);
		for (int i = 0; i < length && total <= MAX_STRING_LENGTH; i++) {
			Object e = array.get(i, array);
			total += e instanceof CharSequence cs ? cs.length() : 1;
		}
		checkString(label, total);
	}

	private void flatSize(String label, NativeArray array, int depth, int level, long[] count) {
		long length = array.getLength();
		for (int i = 0; i < length; i++) {
			Object e = array.get(i, array);
			if (level < depth && e instanceof NativeArray inner) {
				flatSize(label, inner, depth, level + 1, count);
			} else if (++count[0] > MAX_ARRAY_LENGTH) {
				checkArray(label, "the result has", count[0]);
			}
		}
	}

	private static long stringLength(Scriptable thisObj) {
		// reading length does not flatten a concatenated string
		Object length = thisObj == null ? null : ScriptableObject.getProperty(thisObj, "length");
		return length instanceof Number n ? Math.max(0, n.longValue()) : 0;
	}

	private static long resultLength(String method, long length, Object[] args) {
		return switch (method) {
			case "repeat" -> args.length > 0 && args[0] instanceof Number n ? clampedProduct(length, n.doubleValue()) : 0;
			case "padStart", "padEnd" -> args.length > 0 && args[0] instanceof Number n ? (long) Math.min(9e18, n.doubleValue()) : 0;
			case "concat" -> {
				long total = length;
				for (Object a : args) {
					total += a instanceof CharSequence cs ? cs.length() : 24;
				}
				yield total;
			}
			case "replace", "replaceAll" -> {
				if (args.length < 2 || !(args[1] instanceof CharSequence replacement)) {
					yield 0;
				}
				long bound = clampedProduct(length + 1, replacement.length() + 1);
				yield replacement.toString().indexOf('$') >= 0 ? clampedProduct(bound, length + 1) : bound;
			}
			default -> 0;
		};
	}

	private static long clampedProduct(double a, double b) {
		return (long) Math.min(9e18, Math.max(0, a) * Math.max(0, b));
	}

	private void stringArgs(String label, Object[] args) {
		for (Object a : args) {
			if (a instanceof CharSequence cs) {
				checkString(label, cs.length());
			}
		}
	}

	private void checkArray(String label, String what, long length) {
		if (length > MAX_ARRAY_LENGTH) {
			throw session.limit(label + ": " + what + " " + length + " elements, more than the limit of " + MAX_ARRAY_LENGTH);
		}
	}

	private void checkString(String label, long length) {
		if (length > MAX_STRING_LENGTH) {
			throw session.limit(label + ": a string of " + length + " characters exceeds the limit of " + MAX_STRING_LENGTH);
		}
	}
}
