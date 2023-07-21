/*
 * Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Interval;

import java.util.Arrays;

public class UnbufferedTokenStream<T extends Token> implements TokenStream {
	protected TokenSource tokenSource;

	/**
	 * A moving window buffer of the data being scanned. While there's a marker,
	 * we keep adding to buffer. Otherwise, {@link #consume consume()} resets so
	 * we start filling at index 0 again.
	 */
	protected Token[] tokens;

	/**
	 * The number of tokens currently in {@link #tokens tokens}.
	 *
	 * <p>This is not the buffer capacity, that's {@code tokens.length}.</p>
	 */
	protected long n;

	/**
	 * 0..n-1 index into {@link #tokens tokens} of next token.
	 *
	 * <p>
	 * The {@code LT(1)} token is {@code tokens[p]}. If {@code p == n}, we are out
	 * of buffered tokens.
	 * </p>
	 */
	protected long p = 0;

	/**
	 * Count up with {@link #mark mark()} and down with
	 * {@link #release release()}. When we {@code release()} the last mark,
	 * {@code numMarkers} reaches 0 and we reset the buffer. Copy
	 * {@code tokens[p]..tokens[n-1]} to {@code tokens[0]..tokens[(n-1)-p]}.
	 */
	protected int numMarkers = 0;

	/**
	 * This is the {@code LT(-1)} token for the current position.
	 */
	protected Token lastToken;

	/**
	 * When {@code numMarkers > 0}, this is the {@code LT(-1)} token for the
	 * first token in {@link #tokens}. Otherwise, this is {@code null}.
	 */
	protected Token lastTokenBufferStart;

	/**
	 * Absolute token index. It's the index of the token about to be read via
	 * {@code LT(1)}. Goes from 0 to the number of tokens in the entire stream,
	 * although the stream size is unknown before the end is reached.
	 *
	 * <p>This value is used to set the token indexes if the stream provides tokens
	 * that implement {@link WritableToken}.</p>
	 */
	protected long currentTokenIndex = 0;

	public UnbufferedTokenStream(TokenSource tokenSource) {
		this(tokenSource, 256);
	}

	public UnbufferedTokenStream(TokenSource tokenSource, int bufferSize) {
		this.tokenSource = tokenSource;
		tokens = new Token[bufferSize];
		n = 0;
		fill(1); // prime the pump
	}

	@Override
	public Token get(long i) { // get absolute index
		int bufferStartIndex = getBufferStartIndex();
		if (i < bufferStartIndex || i >= bufferStartIndex + n) {
			throw new IndexOutOfBoundsException("get("+i+") outside buffer: "+
			                    bufferStartIndex+".."+(bufferStartIndex+n));
		}
		return tokens[(int) (i - bufferStartIndex)];
	}

	@Override
	public Token LT(int i) {
		if (i == -1) {
			return lastToken;
		}

		sync(i);
		long index = p + i - 1;
		if (index < 0) {
			throw new IndexOutOfBoundsException("LT(" + i + ") gives negative index");
		}

		if (index >= n) {
			assert n > 0 && tokens[(int) (n - 1)].getType() == Token.EOF;
			return tokens[(int) n - 1];
		}

		return tokens[(int) index];
	}

	@Override
	public int LA(int i) {
		return LT(i).getType();
	}

	@Override
	public TokenSource getTokenSource() {
		return tokenSource;
	}


	@Override
	public String getText() {
		return "";
	}


	@Override
	public String getText(RuleContext ctx) {
		return getText(ctx.getSourceInterval());
	}


	@Override
	public String getText(Token start, Token stop) {
		return getText(Interval.of(start.getTokenIndex(), stop.getTokenIndex()));
	}

	@Override
	public void consume() {
		if (LA(1) == Token.EOF) {
			throw new IllegalStateException("cannot consume EOF");
		}

		// buf always has at least tokens[p==0] in this method due to ctor
		lastToken = tokens[(int) p]; // track last token for LT(-1)

		// if we're at last token and no markers, opportunity to flush buffer
		if ( p == n-1 && numMarkers==0 ) {
			n = 0;
			p = -1; // p++ will leave this at 0
			lastTokenBufferStart = lastToken;
		}

		p++;
		currentTokenIndex++;
		sync(1);
	}

	/**
	 * Make sure we have 'need' elements from current position {@link #p p}. Last
	 * valid {@code p} index is {@code tokens.length-1}. {@code p+need-1} is the
	 * tokens index 'need' elements ahead. If we need 1 element, {@code (p+1-1)==p}
	 * must be less than {@code tokens.length}.
	 */
	protected void sync(long want) {
		long need = (p + want - 1) - n + 1; // how many more elements we need?
		if (need > 0) {
			fill(need);
		}
	}

	/**
	 * Add {@code n} elements to the buffer. Returns the number of tokens
	 * actually added to the buffer. If the return value is less than {@code n},
	 * then EOF was reached before {@code n} tokens could be added.
	 */
	protected long fill(long n) {
		for (long i = 0; i < n; i++) {
			if (this.n > 0 && tokens[(int) this.n - 1].getType() == Token.EOF) {
				return i;
			}

			Token t = tokenSource.nextToken();
			add(t);
		}

		return n;
	}

	protected void add(Token t) {
		if ( n>=tokens.length ) {
			tokens = Arrays.copyOf(tokens, tokens.length * 2);
		}

		if (t instanceof WritableToken) {
			((WritableToken)t).setTokenIndex(getBufferStartIndex() + n);
		}

		tokens[(int) n] = t;
		n++;
	}

	/**
	 * Return a marker that we can release later.
	 *
	 * <p>The specific marker value used for this class allows for some level of
	 * protection against misuse where {@code seek()} is called on a mark or
	 * {@code release()} is called in the wrong order.</p>
	 */
	@Override
	public int mark() {
		if (numMarkers == 0) {
			lastTokenBufferStart = lastToken;
		}

		int mark = -numMarkers - 1;
		numMarkers++;
		return mark;
	}

	@Override
	public void release(int marker) {
		int expectedMark = -numMarkers;
		if (marker != expectedMark) {
			throw new IllegalStateException("release() called with an invalid marker.");
		}

		numMarkers--;
		if (numMarkers == 0) { // can we release buffer?
			if (p > 0) {
				// Copy tokens[p]..tokens[n-1] to tokens[0]..tokens[(n-1)-p], reset ptrs
				// p is last valid token; move nothing if p==n as we have no valid char
				System.arraycopy(tokens, (int) p, tokens, 0, (int) (n - p)); // shift n-p tokens from p to 0
				n = n - p;
				p = 0;
			}

			lastTokenBufferStart = lastToken;
		}
	}

	@Override
	public long index() {
		return currentTokenIndex;
	}

	@Override
	public void seek(long index) { // seek to absolute index
		if (index == currentTokenIndex) {
			return;
		}

		if (index > currentTokenIndex) {
			sync(index - currentTokenIndex);
			index = Math.min(index, getBufferStartIndex() + n - 1);
		}

		int bufferStartIndex = getBufferStartIndex();
		long i = index - bufferStartIndex;
		if (i < 0) {
			throw new IllegalArgumentException("cannot seek to negative index " + index);
		} else if (i >= n) {
			throw new UnsupportedOperationException("seek to index outside buffer: " + index + " not in "
					+ bufferStartIndex + ".." + (bufferStartIndex + n));
		}

		p = i;
		currentTokenIndex = index;
		if (p == 0) {
			lastToken = lastTokenBufferStart;
		} else {
			lastToken = tokens[(int) (p - 1)];
		}
	}

	@Override
	public long size() {
		throw new UnsupportedOperationException("Unbuffered stream cannot know its size");
	}

	@Override
	public String getSourceName() {
		return tokenSource.getSourceName();
	}


	@Override
	public String getText(Interval interval) {
		int bufferStartIndex = getBufferStartIndex();
		int bufferStopIndex = bufferStartIndex + tokens.length - 1;

		long start = interval.a;
		long stop = interval.b;
		if (start < bufferStartIndex || stop > bufferStopIndex) {
			throw new UnsupportedOperationException("interval " + interval + " not in token buffer window: "
					+ bufferStartIndex + ".." + bufferStopIndex);
		}

		long a = start - bufferStartIndex;
		long b = stop - bufferStartIndex;

		StringBuilder buf = new StringBuilder();
		for (long i = a; i <= b; i++) {
			Token t = tokens[(int) i];
			buf.append(t.getText());
		}

		return buf.toString();
	}

	protected final int getBufferStartIndex() {
		return (int) (currentTokenIndex - p);
	}
}
