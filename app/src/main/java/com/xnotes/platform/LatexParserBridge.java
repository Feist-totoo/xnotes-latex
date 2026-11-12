package com.xnotes.platform;

import com.hrm.latex.parser.ParseResult;
import com.hrm.latex.parser.ParseSession;
import com.hrm.latex.parser.tokenizer.LatexToken;
import java.util.List;

/** Pinned parser bridge: retain diagnostics when adapting tokens without changing source offsets.
 * ParseSession is JVM-public but Kotlin-internal; the public token-list API drops diagnostics.
 * Keep this dependency boundary beside LatexInternals, without reflection or upstream changes.
 */
public final class LatexParserBridge {
    private LatexParserBridge() {}

    public static ParseResult parse(List<LatexToken> tokens, int inputLength) {
        ParseSession session = new ParseSession(tokens, inputLength);
        return new ParseResult(session.parse(), session.getDiagnostics());
    }
}
