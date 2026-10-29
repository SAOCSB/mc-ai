package io.github.saocsb.mcai.core.script;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Rewrites a script around gaps in Rhino 1.9's ES6 support, without adding or removing line breaks
 * (so line numbers in errors stay valid):
 * <ul>
 * <li>{@code const} becomes {@code let}. Rhino keeps the first value of a {@code const} declared
 * inside a loop body and rejects {@code for (const x of ...)}; {@code let} has neither problem.</li>
 * <li>{@code let x;} becomes {@code let x = undefined;}, because Rhino does not reset an
 * uninitialized {@code let} on each loop iteration.</li>
 * <li>Calls with spread arguments, which Rhino cannot parse, become {@code apply} calls when the
 * callee is a plain name or a dotted name chain: {@code a.b(...xs, 1)} becomes
 * {@code a.b.apply(a, [...xs, 1])}.</li>
 * </ul>
 * The scan understands strings, template literals, comments and regular expression literals, and is
 * best-effort: the engine falls back to the original source when the rewritten one does not compile.
 */
final class SourceRewriter {
	private static final int IDENT = 0;
	private static final int NUMBER = 1;
	private static final int STRING = 2;
	private static final int TEMPLATE = 3;
	private static final int REGEX = 4;
	private static final int PUNCT = 5;

	/** Keywords after which a '/' starts a regular expression literal. */
	private static final Set<String> REGEX_AFTER = Set.of("return", "typeof", "instanceof", "in", "of", "new",
			"delete", "void", "throw", "case", "do", "else", "yield", "await");
	/** Names that cannot be the callee of a rewritable spread call. */
	private static final Set<String> NOT_CALLEE = Set.of("if", "for", "while", "switch", "catch", "with", "function",
			"return", "typeof", "new", "delete", "void", "in", "of", "instanceof", "do", "else", "case", "throw",
			"yield", "await", "let", "const", "var", "class", "super", "import", "export");
	/** Keywords that start a statement; used to find the end of an initializer without a semicolon. */
	private static final Set<String> STATEMENT_START = Set.of("let", "const", "var", "if", "for", "while", "do",
			"return", "function", "switch", "try", "throw", "break", "continue", "class");
	private static final String[] PUNCTUATORS = {">>>=", "...", "===", "!==", "**=", "<<=", ">>=", ">>>", "&&=",
			"||=", "??=", "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=",
			"%=", "&=", "|=", "^=", "**", "<<", ">>"};

	private record Token(int type, int start, int end, boolean newlineBefore, String text) {
		boolean is(String s) {
			return (type == PUNCT || type == IDENT) && text.equals(s);
		}
	}

	private record Edit(int start, int end, String replacement) {
	}

	private SourceRewriter() {
	}

	/** Returns the rewritten source, or the input itself when nothing needs to change. */
	static String rewrite(String src) {
		List<Token> tokens = tokenize(src);
		List<Edit> edits = new ArrayList<>();
		for (int i = 0; i < tokens.size(); i++) {
			Token t = tokens.get(i);
			if (t.type == IDENT && (t.text.equals("const") || t.text.equals("let")) && isDeclaration(tokens, i)) {
				if (t.text.equals("const")) {
					edits.add(new Edit(t.start, t.end, "let"));
				}
				initializeBareLets(tokens, i, edits);
			} else if (t.is("(")) {
				rewriteSpreadCall(tokens, i, edits);
			}
		}
		if (edits.isEmpty()) {
			return src;
		}
		edits.sort(Comparator.comparingInt(Edit::start).reversed());
		StringBuilder sb = new StringBuilder(src);
		for (Edit e : edits) {
			sb.replace(e.start, e.end, e.replacement);
		}
		return sb.toString();
	}

	private static boolean isDeclaration(List<Token> tokens, int i) {
		Token prev = i > 0 ? tokens.get(i - 1) : null;
		if (prev != null && (prev.is(".") || prev.is("?."))) {
			return false;
		}
		Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
		return next != null && (next.type == IDENT || next.is("[") || next.is("{"));
	}

	/** Adds "= undefined" to declarators without an initializer in the declaration at {@code i}. */
	private static void initializeBareLets(List<Token> tokens, int i, List<Edit> edits) {
		if (i >= 2 && tokens.get(i - 1).is("(") && tokens.get(i - 2).is("for")) {
			return;
		}
		int j = i + 1;
		while (j < tokens.size()) {
			Token t = tokens.get(j);
			if (t.is("[") || t.is("{")) {
				j = skipBalanced(tokens, j);
				if (j < 0 || j >= tokens.size() || !tokens.get(j).is("=")) {
					return;
				}
				j = skipExpression(tokens, j + 1);
			} else if (t.type == IDENT) {
				Token next = j + 1 < tokens.size() ? tokens.get(j + 1) : null;
				if (next != null && next.is("=")) {
					j = skipExpression(tokens, j + 2);
				} else {
					if (next == null || next.is(",") || next.is(";") || next.is("}")
							|| (next.newlineBefore && next.type == IDENT)) {
						edits.add(new Edit(t.end, t.end, " = undefined"));
					}
					j++;
				}
			} else {
				return;
			}
			if (j < tokens.size() && tokens.get(j).is(",")) {
				j++;
			} else {
				return;
			}
		}
	}

	/** Index just after the bracket group starting at {@code open}, or -1 if unbalanced. */
	private static int skipBalanced(List<Token> tokens, int open) {
		int depth = 0;
		for (int k = open; k < tokens.size(); k++) {
			Token t = tokens.get(k);
			if (t.is("(") || t.is("[") || t.is("{")) {
				depth++;
			} else if (t.is(")") || t.is("]") || t.is("}")) {
				depth--;
				if (depth == 0) {
					return k + 1;
				}
			}
		}
		return -1;
	}

	/** Index of the token that ends the expression starting at {@code from} (a top-level ',' or ';', or a closer). */
	private static int skipExpression(List<Token> tokens, int from) {
		int depth = 0;
		for (int k = from; k < tokens.size(); k++) {
			Token t = tokens.get(k);
			if (t.is("(") || t.is("[") || t.is("{")) {
				depth++;
			} else if (t.is(")") || t.is("]") || t.is("}")) {
				if (depth == 0) {
					return k;
				}
				depth--;
			} else if (depth == 0) {
				if (t.is(",") || t.is(";")) {
					return k;
				}
				if (k > from && t.newlineBefore && t.type == IDENT && STATEMENT_START.contains(t.text)) {
					return k;
				}
			}
		}
		return tokens.size();
	}

	private static void rewriteSpreadCall(List<Token> tokens, int open, List<Edit> edits) {
		if (open == 0) {
			return;
		}
		Token callee = tokens.get(open - 1);
		if (callee.type != IDENT || NOT_CALLEE.contains(callee.text) || callee.text.equals("this")) {
			return;
		}
		int depth = 0;
		boolean spread = false;
		int close = -1;
		for (int k = open; k < tokens.size(); k++) {
			Token t = tokens.get(k);
			if (t.is("(") || t.is("[") || t.is("{")) {
				depth++;
			} else if (t.is(")") || t.is("]") || t.is("}")) {
				depth--;
				if (depth == 0) {
					close = k;
					break;
				}
			} else if (depth == 1 && t.is("...")) {
				spread = true;
			}
		}
		if (!spread || close < 0 || !tokens.get(close).is(")")) {
			return;
		}
		Token after = close + 1 < tokens.size() ? tokens.get(close + 1) : null;
		if (after != null && (after.is("{") || after.is("=>"))) {
			return; // a method definition or arrow function with rest parameters
		}
		int s = open - 1;
		while (s >= 2 && tokens.get(s - 1).is(".") && tokens.get(s - 2).type == IDENT) {
			s -= 2;
		}
		Token before = s > 0 ? tokens.get(s - 1) : null;
		if (before != null && (before.is(".") || before.is("?.") || before.is("new") || before.is("function"))) {
			return;
		}
		String receiver;
		if (s == open - 1) {
			receiver = "void 0";
		} else {
			StringBuilder sb = new StringBuilder();
			for (int k = s; k < open - 2; k += 2) {
				if (k > s) {
					sb.append('.');
				}
				sb.append(tokens.get(k).text);
			}
			receiver = sb.toString();
		}
		Token o = tokens.get(open);
		Token c = tokens.get(close);
		edits.add(new Edit(o.start, o.end, ".apply(" + receiver + ", ["));
		edits.add(new Edit(c.start, c.end, "])"));
	}

	private static List<Token> tokenize(String src) {
		List<Token> out = new ArrayList<>();
		int n = src.length();
		int i = 0;
		boolean newline = false;
		int braceDepth = 0;
		// brace depths at which an open template substitution (${ ... }) resumes its template
		int[] templateStack = new int[16];
		int templateTop = 0;
		while (i < n) {
			char c = src.charAt(i);
			if (isLineBreak(c)) {
				newline = true;
				i++;
				continue;
			}
			if (Character.isWhitespace(c) || c == ' ' || c == '﻿') {
				i++;
				continue;
			}
			int start = i;
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
				while (i < n && !isLineBreak(src.charAt(i))) {
					i++;
				}
				continue;
			}
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				int endComment = src.indexOf("*/", i + 2);
				int stop = endComment < 0 ? n : endComment + 2;
				for (int k = i; k < stop; k++) {
					if (isLineBreak(src.charAt(k))) {
						newline = true;
					}
				}
				i = stop;
				continue;
			}
			int type;
			if (c == '"' || c == '\'') {
				i++;
				while (i < n && src.charAt(i) != c && !isLineBreak(src.charAt(i))) {
					i += src.charAt(i) == '\\' ? 2 : 1;
				}
				i = Math.min(n, i + 1);
				type = STRING;
			} else if (c == '`' || (c == '}' && templateTop > 0 && templateStack[templateTop - 1] == braceDepth)) {
				if (c == '}') {
					templateTop--;
				}
				i++;
				while (i < n) {
					char d = src.charAt(i);
					if (d == '\\') {
						i += 2;
					} else if (d == '`') {
						i++;
						break;
					} else if (d == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
						i += 2;
						if (templateTop == templateStack.length) {
							templateStack = java.util.Arrays.copyOf(templateStack, templateTop * 2);
						}
						templateStack[templateTop++] = braceDepth;
						break;
					} else {
						i++;
					}
				}
				i = Math.min(n, i);
				type = TEMPLATE;
			} else if (c == '/' && regexAllowed(out)) {
				i++;
				boolean inClass = false;
				while (i < n && !isLineBreak(src.charAt(i))) {
					char d = src.charAt(i);
					if (d == '\\') {
						i += 2;
						continue;
					}
					i++;
					if (d == '[') {
						inClass = true;
					} else if (d == ']') {
						inClass = false;
					} else if (d == '/' && !inClass) {
						break;
					}
				}
				while (i < n && Character.isLetter(src.charAt(i))) {
					i++;
				}
				i = Math.min(n, i);
				type = REGEX;
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
				i++;
				while (i < n) {
					char d = src.charAt(i);
					if (Character.isLetterOrDigit(d) || d == '_' || d == '.') {
						i++;
					} else if ((d == '+' || d == '-') && (src.charAt(i - 1) == 'e' || src.charAt(i - 1) == 'E')) {
						i++;
					} else {
						break;
					}
				}
				type = NUMBER;
			} else if (Character.isJavaIdentifierStart(c) || c == '\\') {
				i++;
				while (i < n && (Character.isJavaIdentifierPart(src.charAt(i)) || src.charAt(i) == '\\')) {
					i++;
				}
				type = IDENT;
			} else {
				i += punctuatorLength(src, i);
				type = PUNCT;
				if (c == '{') {
					braceDepth++;
				} else if (c == '}') {
					braceDepth--;
				}
			}
			out.add(new Token(type, start, i, newline, src.substring(start, i)));
			newline = false;
		}
		return out;
	}

	private static boolean regexAllowed(List<Token> tokens) {
		if (tokens.isEmpty()) {
			return true;
		}
		Token prev = tokens.get(tokens.size() - 1);
		return switch (prev.type) {
			case IDENT -> REGEX_AFTER.contains(prev.text);
			case PUNCT -> !prev.text.equals(")") && !prev.text.equals("]") && !prev.text.equals("++")
					&& !prev.text.equals("--");
			default -> false;
		};
	}

	private static int punctuatorLength(String src, int i) {
		for (String p : PUNCTUATORS) {
			if (src.startsWith(p, i)) {
				if (p.equals("?.") && i + 2 < src.length() && Character.isDigit(src.charAt(i + 2))) {
					continue; // a ? .5 : b
				}
				return p.length();
			}
		}
		return 1;
	}

	private static boolean isLineBreak(char c) {
		return c == '\n' || c == '\r' || c == ' ' || c == ' ';
	}
}
