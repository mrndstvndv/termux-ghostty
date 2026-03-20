package com.termux.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TerminalUrlMatcher {

    static final TerminalUrlMatcher DEFAULT = new TerminalUrlMatcher();

    private static final String SCHEMES_WITH_AUTHORITY =
        "dav|dict|dns|file|finger|ftp(?:s?)|git|gemini|gopher|http(?:s?)|imap(?:s?)|irc(?:[6s]?)|"
            + "ip[fn]s|ldap(?:s?)|pop3(?:s?)|redis(?:s?)|rsync|rtsp(?:[su]?)|sftp|smb(?:s?)|"
            + "smtp(?:s?)|ssh|svn(?:\\+ssh)?|tcp|telnet|tftp|udp|vnc|ws(?:s?)";
    private static final String SCHEMES_WITHOUT_AUTHORITY = "mailto|magnet|news|tel";
    private static final String URL_BODY = "[^\\s\\u0000-\\u001F<>\"']+";
    private static final Pattern MATCHER_PATTERN = Pattern.compile(
        "(?i)\\b(?:(?:" + SCHEMES_WITH_AUTHORITY + ")://|(?:" + SCHEMES_WITHOUT_AUTHORITY + "):)" + URL_BODY,
        Pattern.CASE_INSENSITIVE
    );

    private TerminalUrlMatcher() {
    }

    List<UrlMatch> findMatches(CharSequence text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (text.length() == 0) {
            return Collections.emptyList();
        }

        Matcher matcher = MATCHER_PATTERN.matcher(text);
        List<UrlMatch> matches = new ArrayList<>();
        while (matcher.find()) {
            int start = matcher.start();
            int end = trimMatchEnd(text, start, matcher.end());
            if (end <= start) {
                continue;
            }

            matches.add(new UrlMatch(start, end, text.subSequence(start, end).toString()));
        }
        return matches;
    }

    private static int trimMatchEnd(CharSequence text, int start, int endExclusive) {
        int trimmedEnd = endExclusive;
        while (trimmedEnd > start) {
            char trailing = text.charAt(trimmedEnd - 1);
            if (isAlwaysTrimmedTrailingCharacter(trailing)) {
                trimmedEnd--;
                continue;
            }
            if (isClosingBracket(trailing) && hasUnmatchedClosingBracket(text, start, trimmedEnd, trailing)) {
                trimmedEnd--;
                continue;
            }
            break;
        }
        return trimmedEnd;
    }

    private static boolean isAlwaysTrimmedTrailingCharacter(char value) {
        switch (value) {
            case '.':
            case ',':
            case ':':
            case ';':
            case '!':
            case '?':
            case '\'':
            case '"':
            case '>':
                return true;
            default:
                return false;
        }
    }

    private static boolean isClosingBracket(char value) {
        return value == ')' || value == ']' || value == '}';
    }

    private static boolean hasUnmatchedClosingBracket(CharSequence text, int start, int endExclusive,
                                                      char closingBracket) {
        char openingBracket;
        switch (closingBracket) {
            case ')':
                openingBracket = '(';
                break;
            case ']':
                openingBracket = '[';
                break;
            case '}':
                openingBracket = '{';
                break;
            default:
                return false;
        }

        int balance = 0;
        for (int index = start; index < endExclusive; index++) {
            char value = text.charAt(index);
            if (value == openingBracket) {
                balance++;
                continue;
            }
            if (value == closingBracket) {
                balance--;
            }
        }
        return balance < 0;
    }

    static final class UrlMatch {

        private final int mStart;
        private final int mEndExclusive;
        private final String mUrl;

        UrlMatch(int start, int endExclusive, String url) {
            if (start < 0) {
                throw new IllegalArgumentException("start must be >= 0");
            }
            if (endExclusive <= start) {
                throw new IllegalArgumentException("endExclusive must be > start");
            }
            if (url == null) {
                throw new IllegalArgumentException("url must not be null");
            }

            mStart = start;
            mEndExclusive = endExclusive;
            mUrl = url;
        }

        int getStart() {
            return mStart;
        }

        int getEndExclusive() {
            return mEndExclusive;
        }

        String getUrl() {
            return mUrl;
        }
    }
}
