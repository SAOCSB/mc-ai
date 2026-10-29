package io.github.saocsb.mcai.core.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.Map;

/** Small Gson helpers for the clients. */
final class Json {
	/** Serializes request bodies (no HTML escaping; explicit nulls from extraBody are kept). */
	static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

	private Json() {
	}

	/** Parses JSON, or returns null if it is not valid JSON. */
	static JsonElement parse(String text) {
		try {
			return JsonParser.parseString(text);
		} catch (JsonParseException | IllegalStateException e) {
			return null;
		}
	}

	/** Parses a JSON object, or returns null. */
	static JsonObject parseObject(String text) {
		JsonElement element = parse(text);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	/**
	 * Deep-converts String/Number/Boolean/Map/Iterable/array/JsonElement values. Integral floating-point
	 * numbers become integers (Gson reads untyped JSON numbers as doubles, and providers reject 1024.0 for
	 * integer fields).
	 */
	static JsonElement toJson(Object value) {
		if (value == null) {
			return JsonNull.INSTANCE;
		}
		if (value instanceof JsonElement element) {
			return element.deepCopy();
		}
		if (value instanceof String s) {
			return new JsonPrimitive(s);
		}
		if (value instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		if (value instanceof Number n) {
			return number(n);
		}
		if (value instanceof Map<?, ?> map) {
			JsonObject object = new JsonObject();
			map.forEach((k, v) -> object.add(String.valueOf(k), toJson(v)));
			return object;
		}
		if (value instanceof Iterable<?> iterable) {
			JsonArray array = new JsonArray();
			iterable.forEach(v -> array.add(toJson(v)));
			return array;
		}
		if (value instanceof Object[] items) {
			JsonArray array = new JsonArray();
			for (Object v : items) {
				array.add(toJson(v));
			}
			return array;
		}
		return new JsonPrimitive(value.toString());
	}

	private static JsonElement number(Number n) {
		if (n instanceof Double || n instanceof Float || n instanceof BigDecimal) {
			double d = n.doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				return JsonNull.INSTANCE;
			}
			if (d == Math.rint(d) && Math.abs(d) < 1e15) {
				return new JsonPrimitive((long) d);
			}
		}
		return new JsonPrimitive(n);
	}

	/** The string member, or null if absent, null or not a primitive. */
	static String str(JsonObject object, String key) {
		if (object == null) {
			return null;
		}
		JsonElement e = object.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	static JsonObject obj(JsonObject object, String key) {
		if (object == null) {
			return null;
		}
		JsonElement e = object.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	static JsonArray arr(JsonObject object, String key) {
		if (object == null) {
			return null;
		}
		JsonElement e = object.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	/** The integer member, or {@code fallback} if absent or not a number. */
	static int integer(JsonObject object, String key, int fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement e = object.get(key);
		if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			return (int) Math.min(Integer.MAX_VALUE, Math.max(0, e.getAsLong()));
		}
		return fallback;
	}
}
