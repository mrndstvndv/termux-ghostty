package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class TerminalUrlMatcherTest {

    @Test
    public void trimsTrailingPunctuationButKeepsBalancedParentheses() {
        List<TerminalUrlMatcher.UrlMatch> matches = TerminalUrlMatcher.DEFAULT.findMatches(
            "see https://example.com/path(foo)). and mailto:test@example.com!"
        );

        assertEquals(2, matches.size());
        assertEquals("https://example.com/path(foo)", matches.get(0).getUrl());
        assertEquals("mailto:test@example.com", matches.get(1).getUrl());
        assertTrue(matches.get(0).getStart() < matches.get(0).getEndExclusive());
    }
}
