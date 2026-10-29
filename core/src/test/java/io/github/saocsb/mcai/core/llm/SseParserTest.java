package io.github.saocsb.mcai.core.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseParserTest {
	private final List<String> events = new ArrayList<>();
	private final SseParser parser = new SseParser((event, data) -> events.add(event + "|" + data));

	private void feed(String... lines) {
		for (String line : lines) {
			parser.line(line);
		}
	}

	@Test
	void dispatchesOnBlankLineWithDefaultEventName() {
		feed("data: {\"a\":1}", "", "data: {\"b\":2}", "");
		assertEquals(List.of("message|{\"a\":1}", "message|{\"b\":2}"), events);
	}

	@Test
	void ignoresCommentsAndUnknownFields() {
		feed(": OPENROUTER PROCESSING", "id: 7", "retry: 1000", "foo: bar", "data: x", "", ":keepalive", "");
		assertEquals(List.of("message|x"), events);
	}

	@Test
	void joinsMultiLineDataWithNewlines() {
		feed("data: first", "data:second", "data:  third", "");
		assertEquals(List.of("message|first\nsecond\n third"), events);
	}

	@Test
	void eventNameAppliesToOneEventOnly() {
		feed("event: content_block_delta", "data: {}", "", "data: plain", "");
		assertEquals(List.of("content_block_delta|{}", "message|plain"), events);
	}

	@Test
	void eventWithoutDataIsNotDispatchedAndResetsName() {
		feed("event: ping", "", "data: d", "");
		assertEquals(List.of("message|d"), events);
	}

	@Test
	void emptyDataFieldDispatchesEmptyString() {
		feed("data:", "");
		assertEquals(List.of("message|"), events);
	}

	@Test
	void fieldWithoutColonIsFieldNameWithEmptyValue() {
		feed("data", "");
		assertEquals(List.of("message|"), events);
	}

	@Test
	void finishFlushesUnterminatedEvent() {
		feed("data: [DONE]");
		assertEquals(List.of(), events);
		parser.finish();
		assertEquals(List.of("message|[DONE]"), events);
	}

	@Test
	void stripsLeadingByteOrderMark() {
		feed("﻿data: x", "");
		assertEquals(List.of("message|x"), events);
	}

	@Test
	void valueKeepsColons() {
		feed("data: {\"url\":\"http://x\"}", "");
		assertEquals(List.of("message|{\"url\":\"http://x\"}"), events);
	}
}
